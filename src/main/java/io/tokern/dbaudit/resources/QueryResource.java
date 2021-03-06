package io.tokern.dbaudit.resources;

import com.google.common.cache.Cache;
import io.dropwizard.auth.Auth;
import io.tokern.dbaudit.api.Database;
import io.tokern.dbaudit.api.Query;
import io.tokern.dbaudit.api.User;
import io.tokern.dbaudit.core.executor.Connections;
import io.tokern.dbaudit.core.executor.ThreadPool;
import io.tokern.dbaudit.db.DatabaseDAO;
import io.tokern.dbaudit.db.QueryDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Path("/queries")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class QueryResource {
  private static final Logger logger = LoggerFactory.getLogger(QueryResource.class);
  private final QueryDAO queryDAO;
  private final DatabaseDAO databaseDAO;
  private final Connections connections;
  private final ThreadPool threadPool;
  private final Cache<Long, Future<ThreadPool.Result>> resultCache;

  public QueryResource(QueryDAO queryDAO, DatabaseDAO databaseDAO, Connections connections, ThreadPool threadPool,
                       Cache<Long, Future<ThreadPool.Result>> resultCache) {
    this.queryDAO = queryDAO;
    this.databaseDAO = databaseDAO;
    this.connections = connections;
    this.threadPool = threadPool;
    this.resultCache = resultCache;
  }

  @GET
  public List<Query> list(@Auth User principal) {
    return queryDAO.listByUser(principal.id, principal.orgId);
  }

  @GET
  @Path("{queryId}")
  public Query getQuery(@Auth User principal, @PathParam("queryId") final long queryId) {
    return queryDAO.getById(queryId, principal.orgId);
  }

  private Long startQuery(User principal, Query.RunQueryRequest request) throws NotFoundException, SQLException {
    Database database = databaseDAO.getById(request.dbId, principal.orgId);
    if (database == null) {
      throw new NotFoundException(String.format("Database with id = %d not found", request.dbId));
    }
    Query query = new Query(request.sql, principal.id, database.getId(), principal.orgId);
    Long id = queryDAO.insert(query);
    Query saved = queryDAO.getById(id, principal.orgId);
    Future<ThreadPool.Result> future = threadPool.getService().submit(
        new ThreadPool.Work(saved, queryDAO, connections.getDataSource(query.dbId).getConnection()));
    resultCache.put(id, future);
    return id;
  }

  private Response getQueryResult(User principal, long queryId, boolean isBlocking) throws InterruptedException {
    Query query = queryDAO.getById(queryId, principal.orgId);
    if (query != null) {
      if (isBlocking) {
        while (query.state == Query.State.WAITING || query.state == Query.State.RUNNING) {
          Thread.sleep(200);
          query = queryDAO.getById(queryId, principal.orgId);
        }
      }
      if (query.state == Query.State.WAITING || query.state == Query.State.RUNNING) {
        return Response.status(202).entity(
            String.format("Query %d is in %s state", query.id, query.state.name())).build();
      } else {
        Future<ThreadPool.Result> future = resultCache.getIfPresent(query.id);
        ThreadPool.Result result = null;
        try {
          result = future.get();
        } catch (InterruptedException | ExecutionException exception) {
          logger.warn(String.format("Exception when getting result for %d", query.id), exception);
        }

        int responseCode = 0;
        Object responseObject = null;

        if (query.state == Query.State.ERROR) {
          responseCode = 400;
          responseObject = result != null ? result.throwable : "Query had an ERROR and results are not available";
        } else {
          responseCode = 200;
          responseObject = result != null ? result.resultSet : "Query succeeded but results are not available";
        }

        return Response.status(responseCode).entity(responseObject).build();
      }
    }
    return Response.status(404).entity(String.format("Query %d not found.", queryId)).build();
  }


  @GET
  @Path("{queryId}/results")
  public Response getResults(@Auth User principal, @PathParam("queryId") final long queryId) {
    try {
      return this.getQueryResult(principal, queryId, false);
    } catch (InterruptedException | NotFoundException exception) {
      return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE)
          .entity(exception.getMessage()).build();
    }
  }

  @POST
  public Response createQuery(@Auth User principal, @Valid @NotNull Query.RunQueryRequest request) {
    try {
      Long id = startQuery(principal, request);
      return Response.ok(queryDAO.getById(id, principal.orgId)).build();
    } catch (NotFoundException | SQLException exception) {
      return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE)
          .entity(exception.getMessage()).build();
    }
  }

  @POST
  @Path("/run")
  public Response runQuery(@Auth User principal, @Valid @NotNull Query.RunQueryRequest request) {
    try {
      Long id = startQuery(principal, request);
      return this.getQueryResult(principal, id, true);
    } catch (InterruptedException | NotFoundException | SQLException exception) {
      return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE)
        .entity(exception.getMessage()).build();
    }
  }
}
