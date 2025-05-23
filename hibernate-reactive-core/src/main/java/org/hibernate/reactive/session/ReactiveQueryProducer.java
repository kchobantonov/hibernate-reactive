/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.session;

import org.hibernate.Incubating;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.query.criteria.JpaCriteriaInsert;
import org.hibernate.reactive.common.AffectedEntities;
import org.hibernate.reactive.common.ResultSetMapping;
import org.hibernate.reactive.query.ReactiveMutationQuery;
import org.hibernate.reactive.query.ReactiveNativeQuery;
import org.hibernate.reactive.query.ReactiveQuery;
import org.hibernate.reactive.query.ReactiveQueryImplementor;
import org.hibernate.reactive.query.ReactiveSelectionQuery;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import java.util.concurrent.CompletionStage;


/**
 * Executes queries in a non-blocking fashion.
 *
 * @see org.hibernate.query.QueryProducer
 * @see SharedSessionContractImplementor
 */
@Incubating
public interface ReactiveQueryProducer extends ReactiveConnectionSupplier {

	SessionFactoryImplementor getFactory();

	SharedSessionContractImplementor getSharedContract();

	Dialect getDialect();

	<T> CompletionStage<T> reactiveFetch(T association, boolean unproxy);

	CompletionStage<Object> reactiveInternalLoad(String entityName, Object id, boolean eager, boolean nullable);

	<T> EntityGraph<T> createEntityGraph(Class<T> entity);

	<T> EntityGraph<T> createEntityGraph(Class<T> entity, String name);

	<T> EntityGraph<T> getEntityGraph(Class<T> entity, String name);

	<R> ReactiveQuery<R> createReactiveQuery(String queryString);

	<R> ReactiveQuery<R> createReactiveQuery(TypedQueryReference<R> typedQueryReference);

	<R> ReactiveQuery<R> createReactiveQuery(CriteriaQuery<R> criteriaQuery);

	<R> ReactiveQuery<R> createReactiveQuery(String queryString, Class<R> resultType);

	<R> ReactiveQueryImplementor<R> createReactiveNamedQuery(String queryString);

	<R> ReactiveQueryImplementor<R> createReactiveNamedQuery(String queryString, Class<R> resultType);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String sqlString);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String sqlString, Class<R> resultClass);

	@Deprecated(forRemoval = true)
	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String sqlString, Class<R> resultClass, String tableAlias);

	@Deprecated(forRemoval = true)
	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String sqlString, String resultSetMappingName);

	@Deprecated(forRemoval = true)
	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String sqlString, String resultSetMappingName, Class<R> resultClass);

	<R> ReactiveSelectionQuery<R> createReactiveSelectionQuery(String hqlString, Class<R> resultType);

	<R> ReactiveSelectionQuery<R> createReactiveSelectionQuery(CriteriaQuery<R> criteria);

	<R> ReactiveMutationQuery<R> createReactiveMutationQuery(String hqlString);

	<R> ReactiveMutationQuery<R> createReactiveMutationQuery(CriteriaUpdate<R> updateQuery);

	<R> ReactiveMutationQuery<R> createReactiveMutationQuery(CriteriaDelete<R> deleteQuery);

	<R> ReactiveMutationQuery<R> createReactiveMutationQuery(JpaCriteriaInsert<R> insert);

	<R> ReactiveMutationQuery<R> createNativeReactiveMutationQuery(String sqlString);

	<R> ReactiveSelectionQuery<R> createNamedReactiveSelectionQuery(String name);

	<R> ReactiveSelectionQuery<R> createNamedReactiveSelectionQuery(String name, Class<R> resultType);

	<R> ReactiveMutationQuery<R> createNamedReactiveMutationQuery(String name);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String queryString, AffectedEntities affectedEntities);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String queryString, Class<R> resultType, AffectedEntities affectedEntities);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String queryString, ResultSetMapping<R> resultSetMapping);

	<R> ReactiveNativeQuery<R> createReactiveNativeQuery(String queryString, ResultSetMapping<R> resultSetMapping, AffectedEntities affectedEntities);

	<T> ResultSetMapping<T> getResultSetMapping(Class<T> resultType, String mappingName);
}
