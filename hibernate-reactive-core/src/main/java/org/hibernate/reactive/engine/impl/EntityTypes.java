/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.engine.impl;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.bytecode.enhance.spi.interceptor.EnhancementAsProxyLazinessInterceptor;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.EntityUniqueKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.PersistentAttributeInterceptor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.reactive.engine.spi.ReactiveSharedSessionContractImplementor;
import org.hibernate.reactive.persister.entity.impl.ReactiveEntityPersister;
import org.hibernate.reactive.session.ReactiveQueryProducer;
import org.hibernate.reactive.session.impl.ReactiveQueryExecutorLookup;
import org.hibernate.type.CollectionType;
import org.hibernate.type.EntityType;
import org.hibernate.type.ForeignKeyDirection;
import org.hibernate.type.OneToOneType;
import org.hibernate.type.Type;

import static org.hibernate.bytecode.enhance.spi.LazyPropertyInitializer.UNFETCHED_PROPERTY;
import static org.hibernate.engine.internal.ManagedTypeHelper.asPersistentAttributeInterceptable;
import static org.hibernate.engine.internal.ManagedTypeHelper.isPersistentAttributeInterceptable;
import static org.hibernate.property.access.internal.PropertyAccessStrategyBackRefImpl.UNKNOWN;
import static org.hibernate.proxy.HibernateProxy.extractLazyInitializer;
import static org.hibernate.reactive.engine.impl.ForeignKeys.getEntityIdentifierIfNotUnsaved;
import static org.hibernate.reactive.session.impl.SessionUtil.checkEntityFound;
import static org.hibernate.reactive.util.impl.CompletionStages.completedFuture;
import static org.hibernate.reactive.util.impl.CompletionStages.loop;
import static org.hibernate.reactive.util.impl.CompletionStages.nullFuture;
import static org.hibernate.reactive.util.impl.CompletionStages.voidFuture;

/**
 * Reactive operations that really belong to {@link EntityType}
 *
 * @author Gavin King
 */
public class EntityTypes {

	/*
	 * Replacement for {@link EntityType#resolve(Object, SharedSessionContractImplementor, Object)}
	 */
	public static CompletionStage<Object> resolve(EntityType entityType, Object idOrUniqueKey, Object owner, SharedSessionContractImplementor session) {
		if ( idOrUniqueKey != null && !isNull( entityType, owner, session ) ) {
			if ( entityType.isReferenceToPrimaryKey() ) {
				return ReactiveQueryExecutorLookup.extract( session ).reactiveInternalLoad(
						entityType.getAssociatedEntityName(),
						idOrUniqueKey,
						true,
						entityType.isNullable()
				);
			}
			else {
				return loadByUniqueKey( entityType, idOrUniqueKey, session );
			}
		}
		else {
			return null;
		}
	}

	/**
	 * @see OneToOneType#isNull(Object, SharedSessionContractImplementor)
	 */
	static boolean isNull(EntityType entityType, Object owner, SharedSessionContractImplementor session) {
		if ( entityType instanceof OneToOneType type ) {
			String propertyName = type.getPropertyName();
			if ( propertyName != null ) {
				final EntityPersister ownerPersister = session.getFactory()
						.getRuntimeMetamodels()
						.getMappingMetamodel()
						.getEntityDescriptor( entityType.getAssociatedEntityName() );

				Object id = session.getContextEntityIdentifier( owner );
				EntityKey entityKey = session.generateEntityKey( id, ownerPersister );
				return session.getPersistenceContextInternal().isPropertyNull( entityKey, propertyName );
			}
			else {
				return false;
			}
		}
		else {
			return false;
		}
	}

	/**
	 * Load an instance by a unique key that is not the primary key.
	 *
	 * @param entityType The {@link EntityType} of the association
	 * @param key The unique key property value.
	 * @param session The originating session.
	 * @return The loaded entity
	 * @throws HibernateException generally indicates problems performing the load.
	 */
	static CompletionStage<Object> loadByUniqueKey(
			EntityType entityType,
			Object key,
			SharedSessionContractImplementor session) throws HibernateException {
		SessionFactoryImplementor factory = session.getFactory();
		String entityName = entityType.getAssociatedEntityName();
		String uniqueKeyPropertyName = entityType.getRHSUniqueKeyPropertyName();

		ReactiveEntityPersister persister = (ReactiveEntityPersister) factory
				.getRuntimeMetamodels()
				.getMappingMetamodel()
				.getEntityDescriptor( entityName );

		//TODO: implement 2nd level caching?! natural id caching ?! proxies?!

		EntityUniqueKey euk = new EntityUniqueKey(
				entityName,
				uniqueKeyPropertyName,
				key,
				entityType.getIdentifierOrUniqueKeyType( factory.getRuntimeMetamodels() ),
				factory
		);

		PersistenceContext persistenceContext = session.getPersistenceContextInternal();
		Object result = persistenceContext.getEntity( euk );
		if ( result != null ) {
			return completedFuture( persistenceContext.proxyFor( result ) );
		}
		else {
			return persister
					.reactiveLoadByUniqueKey( uniqueKeyPropertyName, key, session )
					.thenCompose( ukResult -> loadHibernateProxyEntity( ukResult, session )
							.thenApply( targetUK -> {
								persistenceContext.addEntity( euk, targetUK );
								return targetUK;
							} )
					);

		}
	}

	/**
	 * @see org.hibernate.type.TypeHelper#replace(Object[], Object[], Type[], SharedSessionContractImplementor, Object, Map)
	 */
	public static CompletionStage<Object[]> replace(
			final Object[] original,
			final Object[] target,
			final Type[] types,
			final SharedSessionContractImplementor session,
			final Object owner,
			final Map<Object, Object> copyCache) {
		Object[] copied = new Object[original.length];
		return loop( 0, types.length,
				i -> replace( original, target, types, session, owner, copyCache, i, copied )
		).thenApply( v -> copied );
	}

	/**
	 * @see org.hibernate.type.TypeHelper#replace(Object[], Object[], Type[], SharedSessionContractImplementor, Object, Map, ForeignKeyDirection)
	 */
	public static CompletionStage<Object[]> replace(
			final Object[] original,
			final Object[] target,
			final Type[] types,
			final SharedSessionContractImplementor session,
			final Object owner,
			final Map<Object, Object> copyCache,
			final ForeignKeyDirection foreignKeyDirection) {
		Object[] copied = new Object[original.length];
		return loop( 0, types.length,
				i -> replace( original, target, types, session, owner, copyCache, foreignKeyDirection, i, copied )
		).thenApply( v -> copied );
	}

	/**
	 * @see org.hibernate.type.AbstractType#replace(Object, Object, SharedSessionContractImplementor, Object, Map, ForeignKeyDirection)
	 */
	private static CompletionStage<Object> replace(
			EntityType entityType,
			Object original,
			Object target,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache,
			ForeignKeyDirection foreignKeyDirection)
			throws HibernateException {
		boolean include = entityType.isAssociationType()
				? entityType.getForeignKeyDirection() == foreignKeyDirection
				: ForeignKeyDirection.FROM_PARENT == foreignKeyDirection;
		return include
				? replace( entityType, original, target, session, owner, copyCache )
				: completedFuture( target );
	}

	/**
	 * @see EntityType#replace(Object, Object, SharedSessionContractImplementor, Object, Map)
	 */
	protected static CompletionStage<Object> replace(
			EntityType entityType,
			Object original,
			Object target,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache) {
		if ( original == null ) {
			return nullFuture();
		}
		Object cached = copyCache.get( original );
		if ( cached != null ) {
			return completedFuture( cached );
		}
		else {
			if ( original == target ) {
				return completedFuture( target );
			}
			if ( session.getContextEntityIdentifier( original ) == null ) {
				return ForeignKeys.isTransient( entityType.getAssociatedEntityName(), original, false, session )
						.thenCompose( isTransient -> {
							if ( isTransient ) {
								// original is transient; it is possible that original is a "managed" entity that has
								// not been made persistent yet, so check if copyCache contains original as a "managed" value
								// that corresponds with some "merge" value.
								if ( copyCache.containsValue( original ) ) {
									return completedFuture( original );
								}
								else {
									// the transient entity is not "managed"; add the merge/managed pair to copyCache
									final Object copy = session.getEntityPersister( entityType.getAssociatedEntityName(), original )
											.instantiate( null, session );
									copyCache.put( original, copy );
									return completedFuture( copy );
								}
							}
							else {
								return resolveIdOrUniqueKey( entityType, original, session, owner, copyCache );
							}
						} );
			}
			else {
				return resolveIdOrUniqueKey( entityType, original, session, owner, copyCache );
			}
		}
	}

	private static CompletionStage<Object> resolveIdOrUniqueKey(
			EntityType entityType,
			Object original,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache) {
		return getIdentifier( entityType, original, session )
				.thenCompose( id -> {
					if ( id == null ) {
						throw new AssertionFailure( "non-transient entity has a null id: " + original.getClass()
								.getName() );
					}
					// For the special case of a @ManyToOne joined on a (non-primary) unique key,
					// the "id" class is actually the associated entity object itself, but treated
					// as a ComponentType. In the case that the entity is unfetched, we need to
					// explicitly fetch it here before calling replace(). (Note that in Hibernate
					// ORM this is unnecessary due to transparent lazy fetching.)
					return ( (ReactiveQueryProducer) session )
							.reactiveFetch( id, true )
							.thenCompose( fetched -> {
								Object idOrUniqueKey = entityType
										.getIdentifierOrUniqueKeyType( session.getFactory().getRuntimeMetamodels() )
										.replace( fetched, null, session, owner, copyCache );
								if ( idOrUniqueKey instanceof CompletionStage ) {
									return ( (CompletionStage<?>) idOrUniqueKey )
											.thenCompose( key -> resolve( entityType, key, owner, session ) );
								}
								return resolve( entityType, idOrUniqueKey, owner, session );
							} );
				} );
	}

	/**
	 * see EntityType#getIdentifier(Object, SharedSessionContractImplementor)
	 */
	private static CompletionStage<Object> getIdentifier(
			EntityType entityType,
			Object value,
			SharedSessionContractImplementor session) {
		if ( entityType.isReferenceToIdentifierProperty() ) {
			// tolerates nulls
			return getEntityIdentifierIfNotUnsaved( entityType.getAssociatedEntityName(), value, session );
		}
		if ( value == null ) {
			return nullFuture();
		}

		if ( value instanceof HibernateProxy ) {
			return getIdentifierFromHibernateProxy( entityType, (HibernateProxy) value, session );
		}

		final LazyInitializer lazyInitializer = extractLazyInitializer( value );
		if ( lazyInitializer != null ) {
			/*
				If the value is a Proxy and the property access is field, the value returned by
			 	`attributeMapping.getAttributeMetadata().getPropertyAccess().getGetter().get( object )`
			 	is always null except for the id, we need the to use the proxy implementation to
			 	extract the property value.
			 */
			value = lazyInitializer.getImplementation();
		}
		else if ( isPersistentAttributeInterceptable( value ) ) {
				/*
					If the value is an instance of PersistentAttributeInterceptable, and it is not initialized
					we need to force initialization the get the property value
				 */
			final PersistentAttributeInterceptor interceptor = asPersistentAttributeInterceptable( value ).$$_hibernate_getInterceptor();
			if ( interceptor instanceof EnhancementAsProxyLazinessInterceptor ) {
				( (EnhancementAsProxyLazinessInterceptor) interceptor ).forceInitialize( value, null );
			}
		}
		final EntityPersister entityPersister = entityType.getAssociatedEntityPersister( session.getFactory() );
		String uniqueKeyPropertyName = entityType.getRHSUniqueKeyPropertyName();
		Object propertyValue = entityPersister.getPropertyValue( value, uniqueKeyPropertyName );
		// We now have the value of the property-ref we reference. However,
		// we need to dig a little deeper, as that property might also be
		// an entity type, in which case we need to resolve its identifier
		final AttributeMapping type = entityPersister.findAttributeMapping( uniqueKeyPropertyName );
		if ( type.isEntityIdentifierMapping() ) {
			propertyValue = getIdentifier( (EntityType) type, propertyValue, session );
		}
		return completedFuture( propertyValue );

	}

	private static CompletionStage<Object> getIdentifierFromHibernateProxy(
			EntityType entityType,
			HibernateProxy proxy,
			SharedSessionContractImplementor session) {
		final LazyInitializer initializer = proxy.getHibernateLazyInitializer();
		final String entityName = initializer.getEntityName();
		final Object identifier = initializer.getIdentifier();
		return ( (ReactiveSharedSessionContractImplementor) session )
				.reactiveImmediateLoad( entityName, identifier )
				.thenApply( entity -> {
					checkEntityFound( session, entityName, identifier, entity );
					initializer.setSession( session );
					initializer.setImplementation( entity );
					if ( entity != null ) {
						final EntityPersister entityPersister = entityType.getAssociatedEntityPersister( session.getFactory() );
						String uniqueKeyPropertyName = entityType.getRHSUniqueKeyPropertyName();
						Object propertyValue = entityPersister.getPropertyValue( entity, uniqueKeyPropertyName );
						// We now have the value of the property-ref we reference. However,
						// we need to dig a little deeper, as that property might also be
						// an entity type, in which case we need to resolve its identifier
						final AttributeMapping type = entityPersister.findAttributeMapping( uniqueKeyPropertyName );
						if ( type.isEntityIdentifierMapping() ) {
							propertyValue = getIdentifier( (EntityType) type, propertyValue, session );
						}
						return propertyValue;
					}
					return null;
				} );
	}

	private static CompletionStage<Object> loadHibernateProxyEntity(
			Object entity,
			SharedSessionContractImplementor session) {
		if ( entity instanceof HibernateProxy ) {
			LazyInitializer initializer = ( (HibernateProxy) entity ).getHibernateLazyInitializer();
			final String entityName = initializer.getEntityName();
			final Object identifier = initializer.getIdentifier();
			return ( (ReactiveSharedSessionContractImplementor) session )
					.reactiveImmediateLoad( entityName, identifier )
					.thenApply( result -> {
						checkEntityFound( session, entityName, identifier, result );
						return result;
					} );
		}
		else {
			return completedFuture( entity );
		}
	}

	private static CompletionStage<Void> replace(
			Object[] original,
			Object[] target,
			Type[] types,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache,
			int i,
			Object[] copied) {
		if ( original[i] == UNFETCHED_PROPERTY || original[i] == UNKNOWN ) {
			copied[i] = target[i];
			return voidFuture();
		}
		else if ( types[i] instanceof CollectionType ) {
			return CollectionTypes.replace(
					(CollectionType) types[i],
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache
			).thenAccept( copy -> copied[i] = copy );
		}
		else if ( types[i] instanceof EntityType ) {
			return replace(
					(EntityType) types[i],
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache
			).thenAccept( copy -> copied[i] = copy );
		}
		else {
			final Type type = types[i];
			copied[i] = type.replace(
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache
			);
			return voidFuture();
		}
	}

	private static CompletionStage<Void> replace(
			Object[] original,
			Object[] target,
			Type[] types,
			SharedSessionContractImplementor session,
			Object owner,
			Map<Object, Object> copyCache,
			ForeignKeyDirection foreignKeyDirection,
			int i,
			Object[] copied) {
		if ( original[i] == UNFETCHED_PROPERTY || original[i] == UNKNOWN ) {
			copied[i] = target[i];
			return voidFuture();
		}
		else if ( types[i] instanceof CollectionType ) {
			return CollectionTypes.replace(
					(CollectionType) types[i],
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache,
					foreignKeyDirection
			).thenAccept( copy -> copied[i] = copy );
		}
		else if ( types[i] instanceof EntityType ) {
			return replace(
					(EntityType) types[i],
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache,
					foreignKeyDirection
			).thenAccept( copy -> copied[i] = copy );
		}
		else {
			copied[i] = types[i].replace(
					original[i],
					target[i] == UNFETCHED_PROPERTY ? null : target[i],
					session,
					owner,
					copyCache,
					foreignKeyDirection
			);
			return voidFuture();
		}
	}


}
