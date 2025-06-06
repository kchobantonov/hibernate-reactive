/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.loader.ast.internal;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.engine.spi.BatchFetchQueue;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.engine.spi.SubselectFetch;
import org.hibernate.loader.ast.internal.LoaderSelectBuilder;
import org.hibernate.loader.ast.internal.MultiIdEntityLoaderArrayParam;
import org.hibernate.loader.ast.internal.MultiKeyLoadLogging;
import org.hibernate.loader.ast.spi.MultiIdLoadOptions;
import org.hibernate.loader.internal.CacheLoadHelper.PersistenceContextEntry;
import org.hibernate.metamodel.mapping.BasicEntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.reactive.sql.exec.internal.StandardReactiveSelectExecutor;
import org.hibernate.reactive.sql.results.spi.ReactiveListResultsConsumer;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.exec.internal.JdbcParameterBindingImpl;
import org.hibernate.sql.exec.internal.JdbcParameterBindingsImpl;
import org.hibernate.sql.exec.internal.JdbcParameterImpl;
import org.hibernate.sql.exec.spi.JdbcOperationQuerySelect;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcParametersList;
import org.hibernate.sql.results.internal.RowTransformerStandardImpl;

import static org.hibernate.event.spi.LoadEventListener.GET;
import static org.hibernate.internal.util.collections.CollectionHelper.arrayList;
import static org.hibernate.internal.util.collections.CollectionHelper.isEmpty;
import static org.hibernate.loader.ast.internal.MultiKeyLoadHelper.resolveArrayJdbcMapping;
import static org.hibernate.loader.internal.CacheLoadHelper.loadFromSessionCache;
import static org.hibernate.reactive.loader.ast.internal.ReactiveLoaderHelper.loadByArrayParameter;
import static org.hibernate.reactive.util.impl.CompletionStages.completedFuture;
import static org.hibernate.reactive.util.impl.CompletionStages.loop;
import static org.hibernate.reactive.util.impl.CompletionStages.voidFuture;

/**
 * @see org.hibernate.loader.ast.internal.MultiIdEntityLoaderArrayParam
 */
public class ReactiveMultiIdEntityLoaderArrayParam<E> extends ReactiveAbstractMultiIdEntityLoader<E> {

	private final JdbcMapping arrayJdbcMapping;
	private final JdbcParameter jdbcParameter;

	public ReactiveMultiIdEntityLoaderArrayParam(
			EntityMappingType entityDescriptor,
			SessionFactoryImplementor sessionFactory) {
		super( entityDescriptor, sessionFactory );
		final Class<?> idClass = getIdentifierMapping().getJavaType().getJavaTypeClass();
		arrayJdbcMapping = resolveArrayJdbcMapping(
				getIdentifierMapping().getJdbcMapping(),
				idClass,
				getSessionFactory()
		);
		jdbcParameter = new JdbcParameterImpl( arrayJdbcMapping );
	}

	@Override
	public BasicEntityIdentifierMapping getIdentifierMapping() {
		return (BasicEntityIdentifierMapping) super.getIdentifierMapping();
	}

	@Override
	protected <K> CompletionStage<List<E>> performOrderedMultiLoad(
			K[] ids,
			MultiIdLoadOptions loadOptions,
			SharedSessionContractImplementor session) {
		if ( MultiKeyLoadLogging.MULTI_KEY_LOAD_LOGGER.isTraceEnabled() ) {
			MultiKeyLoadLogging.MULTI_KEY_LOAD_LOGGER.tracef(
					"ReactiveMultiIdEntityLoaderArrayParam#performOrderedMultiLoad - %s",
					getLoadable().getEntityName()
			);
		}

		final boolean coerce = isIdCoercionEnabled();
		final LockOptions lockOptions = ( loadOptions.getLockOptions() == null )
				? new LockOptions( LockMode.NONE )
				: loadOptions.getLockOptions();

		final List<Object> result = arrayList( ids.length );
		final List<Object> idsToLoadFromDatabase = new ArrayList<>();
		final List<Integer> idsToLoadFromDatabaseResultIndexes = new ArrayList<>();

		return loop( 0, ids.length, i -> {
			final Object id = coerce
					? getLoadable().getIdentifierMapping().getJavaType().coerce( ids[i], session )
					: ids[i];

			final EntityKey entityKey = new EntityKey( id, getLoadable().getEntityPersister() );

			if ( loadOptions.isSessionCheckingEnabled() || loadOptions.isSecondLevelCacheCheckingEnabled() ) {
				Object managedEntity = null;

				if ( loadOptions.isSessionCheckingEnabled() ) {
					// look for it in the Session first
					final PersistenceContextEntry persistenceContextEntry = loadFromSessionCache(
							entityKey,
							lockOptions,
							GET,
							session
					);
					managedEntity = persistenceContextEntry.entity();

					if ( managedEntity != null
							&& !loadOptions.isReturnOfDeletedEntitiesEnabled()
							&& !persistenceContextEntry.isManaged() ) {
						// put a null in the result
						result.add( i, null );
						return voidFuture();
					}
				}

				if ( managedEntity == null && loadOptions.isSecondLevelCacheCheckingEnabled() ) {
					final EntityPersister persister = getLoadable().getEntityPersister();
					// look for it in the SessionFactory
					managedEntity = session.loadFromSecondLevelCache( persister, entityKey, null, lockOptions.getLockMode() );
				}

				if ( managedEntity != null ) {
					result.add( i, managedEntity );
					return voidFuture();
				}
			}

			// hold its place in the result with the EntityKey, we'll come back to it later
			result.add( i, entityKey );
			idsToLoadFromDatabase.add( id );
			idsToLoadFromDatabaseResultIndexes.add( i );
			return voidFuture();
		} )
				.thenCompose( v -> {
					if ( idsToLoadFromDatabase.isEmpty() ) {
						// all the given ids were already associated with the Session
						//noinspection unchecked
						return completedFuture( (List<E>) result );
					}

					final SelectStatement sqlAst = LoaderSelectBuilder.createSelectBySingleArrayParameter(
							getLoadable(),
							getIdentifierMapping(),
							session.getLoadQueryInfluencers(),
							lockOptions,
							jdbcParameter,
							getSessionFactory()
					);
					final JdbcOperationQuerySelect jdbcSelectOperation = getSessionFactory().getJdbcServices()
							.getJdbcEnvironment()
							.getSqlAstTranslatorFactory()
							.buildSelectTranslator( getSessionFactory(), sqlAst )
							.translate( JdbcParameterBindings.NO_BINDINGS, QueryOptions.NONE );

					final JdbcParameterBindings jdbcParameterBindings = new JdbcParameterBindingsImpl( 1 );
					jdbcParameterBindings
							.addBinding(
									jdbcParameter,
									new JdbcParameterBindingImpl(
											arrayJdbcMapping,
											idsToLoadFromDatabase.toArray( createTypedArray( 0 ) )
									)
							);

					final PersistenceContext persistenceContext = session.getPersistenceContext();
					final BatchFetchQueue batchFetchQueue = persistenceContext.getBatchFetchQueue();

					final SubselectFetch.RegistrationHandler subSelectFetchableKeysHandler = SubselectFetch.createRegistrationHandler(
							batchFetchQueue,
							sqlAst,
							JdbcParametersList.singleton( jdbcParameter ),
							jdbcParameterBindings
					);

					return StandardReactiveSelectExecutor.INSTANCE
							.list(
									jdbcSelectOperation,
									jdbcParameterBindings,
									new ExecutionContextWithSubselectFetchHandler(
											session,
											subSelectFetchableKeysHandler
									),
									RowTransformerStandardImpl.instance(),
									ReactiveListResultsConsumer.UniqueSemantic.FILTER
							);
				} )
				.thenApply( ignore -> {
					final PersistenceContext persistenceContext = session.getPersistenceContext();
					for ( final Integer resultIndex : idsToLoadFromDatabaseResultIndexes ) {
						// the element value at this position in the result List should be
						// the EntityKey for that entity - reuse it
						final EntityKey entityKey = (EntityKey) result.get( resultIndex );
						session.getPersistenceContextInternal().getBatchFetchQueue().removeBatchLoadableEntityKey( entityKey );
						Object entity = persistenceContext.getEntity( entityKey );
						if ( entity != null && !loadOptions.isReturnOfDeletedEntitiesEnabled() ) {
							// make sure it is not DELETED
							final EntityEntry entry = persistenceContext.getEntry( entity );
							if ( entry.getStatus().isDeletedOrGone() ) {
								// the entity is locally deleted, and the options ask that we not return such entities...
								entity = null;
							}
						}
						result.set( resultIndex, entity );
					}

					//noinspection unchecked
					return (List<E>) result;
				} );
	}

	@Override
	protected <K> CompletionStage<List<E>> performUnorderedMultiLoad(
			K[] ids,
			MultiIdLoadOptions loadOptions,
			SharedSessionContractImplementor session) {
		if ( MultiKeyLoadLogging.MULTI_KEY_LOAD_LOGGER.isTraceEnabled() ) {
			MultiKeyLoadLogging.MULTI_KEY_LOAD_LOGGER.tracef(
					"ReactiveMultiIdEntityLoaderArrayParam#performUnorderedMultiLoad - %s",
					getLoadable().getEntityName()
			);
		}

		final List<E> result = arrayList( ids.length );
		final LockOptions lockOptions = ( loadOptions.getLockOptions() == null )
				? new LockOptions( LockMode.NONE )
				: loadOptions.getLockOptions();

		//noinspection unchecked
		final K[] idsToLoadFromDatabase = processResolvableEntities(
				ids,
				(index, entityKey, resolvedEntity) -> result.add( (E) resolvedEntity ),
				loadOptions,
				lockOptions,
				session
		);

		if ( idsToLoadFromDatabase == null ) {
			// all the given ids were already associated with the Session
			return completedFuture( result );
		}

		final SelectStatement sqlAst = LoaderSelectBuilder.createSelectBySingleArrayParameter(
				getLoadable(),
				getIdentifierMapping(),
				session.getLoadQueryInfluencers(),
				lockOptions,
				jdbcParameter,
				getSessionFactory()
		);

		final JdbcOperationQuerySelect jdbcSelectOperation = getSessionFactory().getJdbcServices()
				.getJdbcEnvironment()
				.getSqlAstTranslatorFactory()
				.buildSelectTranslator( getSessionFactory(), sqlAst )
				.translate( JdbcParameterBindings.NO_BINDINGS, QueryOptions.NONE );

		return loadByArrayParameter(
				idsToLoadFromDatabase,
				sqlAst,
				jdbcSelectOperation,
				jdbcParameter,
				arrayJdbcMapping,
				null,
				null,
				lockOptions,
				session.isDefaultReadOnly(),
				session
		).thenApply( databaseResults -> {
			result.addAll( (Collection<? extends E>) databaseResults );

			//noinspection ForLoopReplaceableByForEach
			for ( int i = 0; i < idsToLoadFromDatabase.length; i++ ) {
				final Object id = idsToLoadFromDatabase[i];
				if ( id == null ) {
					// skip any of the null padded ids
					//		- actually we could probably even break here
					continue;
				}
				// found or not, remove the key from the batch-fetch queue
				session.getPersistenceContextInternal().getBatchFetchQueue()
						.removeBatchLoadableEntityKey( session.generateEntityKey( id, getLoadable().getEntityPersister() ) );
			}

			return result;
		} );
	}

	protected final <R, K> K[] processResolvableEntities(
			K[] ids,
			MultiIdEntityLoaderArrayParam.ResolutionConsumer<R> resolutionConsumer,
			MultiIdLoadOptions loadOptions,
			LockOptions lockOptions,
			SharedSessionContractImplementor session) {
		if ( !loadOptions.isSessionCheckingEnabled()
				&& !loadOptions.isSecondLevelCacheCheckingEnabled() ) {
			// we'll load all of them from the database
			return ids;
		}

		final boolean coerce = isIdCoercionEnabled();

		boolean foundAnyResolvedEntities = false;
		List<K> nonResolvedIds = null;

		for ( int i = 0; i < ids.length; i++ ) {
			final Object id;
			if ( coerce ) {
				id = getLoadable().getIdentifierMapping().getJavaType().coerce( ids[i], session );
			}
			else {
				id = ids[i];
			}

			final EntityKey entityKey = new EntityKey( id, getLoadable().getEntityPersister() );
			Object resolvedEntity = null;

			// look for it in the Session first
			final PersistenceContextEntry persistenceContextEntry = loadFromSessionCache(
					entityKey,
					lockOptions,
					GET,
					session
			);
			if ( loadOptions.isSessionCheckingEnabled() ) {
				resolvedEntity = persistenceContextEntry.entity();

				if ( resolvedEntity != null
						&& !loadOptions.isReturnOfDeletedEntitiesEnabled()
						&& !persistenceContextEntry.isManaged() ) {
					foundAnyResolvedEntities = true;
					resolutionConsumer.consume( i, entityKey, null );
					continue;
				}
			}

			if ( resolvedEntity == null && loadOptions.isSecondLevelCacheCheckingEnabled() ) {
				final EntityPersister persister = getLoadable().getEntityPersister();
				resolvedEntity = session.loadFromSecondLevelCache( persister, entityKey, null, lockOptions.getLockMode() );
			}

			if ( resolvedEntity != null ) {
				foundAnyResolvedEntities = true;

				//noinspection unchecked
				resolutionConsumer.consume( i, entityKey, (R) resolvedEntity );
			}
			else {
				if ( nonResolvedIds == null ) {
					nonResolvedIds = new ArrayList<>();
				}
				nonResolvedIds.add( (K) id );
			}
		}

		if ( foundAnyResolvedEntities ) {
			if ( isEmpty( nonResolvedIds ) ) {
				// all the given ids were already associated with the Session
				return null;
			}

			return nonResolvedIds.toArray( createTypedArray( 0 ) );
		}

		return ids;
	}

	private <X> X[] createTypedArray(@SuppressWarnings("SameParameterValue") int length) {
		//noinspection unchecked
		return (X[]) Array.newInstance( getIdentifierMapping().getJavaType().getJavaTypeClass(), length );
	}
}
