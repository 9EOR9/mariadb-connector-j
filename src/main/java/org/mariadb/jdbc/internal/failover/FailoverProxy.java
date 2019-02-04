/*
 *
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2017 MariaDB Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 * This particular MariaDB Client for Java file is work
 * derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
 * the following copyright and notice provisions:
 *
 * Copyright (c) 2009-2011, Marcus Eriksson
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * Neither the name of the driver nor the names of its contributors may not be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package org.mariadb.jdbc.internal.failover;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.MariaDbConnection;
import org.mariadb.jdbc.MariaDbStatement;
import org.mariadb.jdbc.internal.logging.Logger;
import org.mariadb.jdbc.internal.logging.LoggerFactory;
import org.mariadb.jdbc.internal.protocol.Protocol;
import org.mariadb.jdbc.internal.util.dao.ServerPrepareResult;
import org.mariadb.jdbc.internal.util.exceptions.ExceptionMapper;


public class FailoverProxy implements InvocationHandler {

  private static final String METHOD_IS_EXPLICIT_CLOSED = "isExplicitClosed";
  private static final String METHOD_GET_OPTIONS = "getOptions";
  private static final String METHOD_GET_URLPARSER = "getUrlParser";
  private static final String METHOD_GET_PROXY = "getProxy";
  private static final String METHOD_EXECUTE_QUERY = "executeQuery";
  private static final String METHOD_SET_READ_ONLY = "setReadonly";
  private static final String METHOD_GET_READ_ONLY = "getReadonly";
  private static final String METHOD_IS_MASTER_CONNECTION = "isMasterConnection";
  private static final String METHOD_VERSION_GREATER_OR_EQUAL = "versionGreaterOrEqual";
  private static final String METHOD_SESSION_STATE_AWARE = "sessionStateAware";
  private static final String METHOD_CLOSED_EXPLICIT = "closeExplicit";
  private static final String METHOD_ABORT = "abort";
  private static final String METHOD_IS_CLOSED = "isClosed";
  private static final String METHOD_EXECUTE_PREPARED_QUERY = "executePreparedQuery";
  private static final String METHOD_COM_MULTI_PREPARE_EXECUTES = "prepareAndExecutesComMulti";
  private static final String METHOD_PROLOG_PROXY = "prologProxy";
  private static final String METHOD_RESET = "reset";
  private static final String METHOD_IS_VALID = "isValid";
  private static final String METHOD_GET_LOCK = "getLock";
  private static final String METHOD_GET_NO_BACKSLASH = "noBackslashEscapes";
  private static final String METHOD_GET_SERVER_THREAD_ID = "getServerThreadId";
  private static final String METHOD_PROLOG = "prolog";
  private static final String METHOD_GET_CATALOG = "getCatalog";
  private static final String METHOD_GET_TIMEOUT = "getTimeout";
  private static final String METHOD_GET_MAJOR_VERSION = "getMajorServerVersion";


  private static final Logger logger = LoggerFactory.getLogger(FailoverProxy.class);
  public final ReentrantLock lock;

  private final Listener listener;

  /**
   * Proxy constructor.
   *
   * @param listener failover implementation.
   * @param lock     synchronisation lock
   * @throws SQLException if connection error occur
   */
  public FailoverProxy(Listener listener, ReentrantLock lock) throws SQLException {
    this.lock = lock;
    this.listener = listener;
    this.listener.setProxy(this);
    this.listener.initializeConnection();
  }

  /**
   * Add Host information ("on HostAddress...") to exception.
   *
   * <p>example :
   * java.sql.SQLException: (conn=603) Cannot execute statement in a READ ONLY transaction.<br/>
   * Query is: INSERT INTO TableX VALUES (21)<br/> on HostAddress{host='mydb.example.com',
   * port=3306},master=true</p>
   *
   * @param exception current exception
   * @param protocol  protocol to have hostname
   */
  private static SQLException addHostInformationToException(SQLException exception,
      Protocol protocol) {
    if (protocol != null) {
      return new SQLException(exception.getMessage()
          + "\non " + protocol.getHostAddress().toString() + ",master="
          + protocol.isMasterConnection(), exception.getSQLState(), exception.getErrorCode(),
          exception.getCause());

    }
    return exception;
  }

  /**
   * Proxy that catch Protocol call, to permit to catch errors and handle failover when multiple
   * hosts.
   *
   * @param proxy  the current protocol
   * @param method the called method on the protocol
   * @param args   methods parameters
   * @return protocol method result
   * @throws Throwable the method throwed error if not catch by failover
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String methodName = method.getName();
    if (METHOD_GET_LOCK.equals(methodName)) {
      return this.lock;
    } else if (METHOD_GET_NO_BACKSLASH.equals(methodName)) {
      return listener.noBackslashEscapes();
    } else if (METHOD_GET_CATALOG.equals(methodName)) {
      return listener.getCatalog();
    } else if (METHOD_GET_TIMEOUT.equals(methodName)) {
      return listener.getTimeout();
    } else if (METHOD_VERSION_GREATER_OR_EQUAL.equals(methodName)) {
      return listener.versionGreaterOrEqual((Integer) args[0], (Integer) args[1], (Integer) args[2]);
    } else if (METHOD_SESSION_STATE_AWARE.equals(methodName)) {
      return listener.sessionStateAware();
    } else if (METHOD_IS_EXPLICIT_CLOSED.equals(methodName)) {
      return listener.isExplicitClosed();
    } else if (METHOD_GET_OPTIONS.equals(methodName)) {
      return listener.getUrlParser().getOptions();
    } else if (METHOD_GET_MAJOR_VERSION.equals(methodName)) {
      return listener.getMajorServerVersion();
    } else if (METHOD_GET_SERVER_THREAD_ID.equals(methodName)) {
      return listener.getServerThreadId();
    } else if (METHOD_GET_URLPARSER.equals(methodName)) {
      return listener.getUrlParser();
    } else if (METHOD_GET_PROXY.equals(methodName)) {
      return this;
    } else if (METHOD_IS_CLOSED.equals(methodName)) {
      return listener.isClosed();
    } else if (METHOD_IS_VALID.equals(methodName)) {
      return listener.isValid((Integer) args[0]);
    } else if (METHOD_PROLOG.equals(methodName)) {
      listener.prolog((Long) args[0], (MariaDbConnection) args[2], (MariaDbStatement) args[3]);
      return null;
    } else if (METHOD_EXECUTE_QUERY.equals(methodName)) {
      try {
        this.listener.preExecute();
      } catch (SQLException e) {
        //handle failover only if connection error
        //normal error can be thrown upon reconnection if there was a transaction in progress.
        if (hasToHandleFailover(e)) {
          return handleFailOver(e, method, args, listener.getCurrentProtocol());
        }
      }
    } else if (METHOD_SET_READ_ONLY.equals(methodName)) {
      this.listener.switchReadOnlyConnection((Boolean) args[0]);
      return null;
    } else if (METHOD_GET_READ_ONLY.equals(methodName)) {
      return this.listener.isReadOnly();
    } else if (METHOD_IS_MASTER_CONNECTION.equals(methodName)) {
      return this.listener.isMasterConnection();
    } else if (METHOD_ABORT.equals(methodName)) {
      this.listener.preAbort();
      return null;
    } else if (METHOD_CLOSED_EXPLICIT.equals(methodName)) {
      this.listener.preClose();
      return null;
    } else if (METHOD_COM_MULTI_PREPARE_EXECUTES.equals(methodName)
        || METHOD_EXECUTE_PREPARED_QUERY.equals(methodName)) {
      boolean mustBeOnMaster = (Boolean) args[0];
      ServerPrepareResult serverPrepareResult = (ServerPrepareResult) args[1];
      if (serverPrepareResult != null) {
        if (!mustBeOnMaster && serverPrepareResult.getUnProxiedProtocol().isMasterConnection()
            && !this.listener.hasHostFail()) {
          //PrepareStatement was to be executed on slave, but since a failover was running on master connection. Slave connection is up
          // again, so has to be re-prepared on slave
          try {
            logger.trace("re-prepare query \"{}\" on slave (was "
                + "temporary on master since failover)", serverPrepareResult.getSql());
            this.listener.rePrepareOnSlave(serverPrepareResult, false);
          } catch (SQLException q) {
            //error during re-prepare, will do executed on master.
          }
        }
        try {
          return listener.invoke(method, args, serverPrepareResult.getUnProxiedProtocol());
        } catch (InvocationTargetException e) {
          if (e.getTargetException() != null) {
            if (e.getTargetException() instanceof SQLException
                && hasToHandleFailover((SQLException) e.getTargetException())) {
              return handleFailOver((SQLException) e.getTargetException(), method, args,
                  serverPrepareResult.getUnProxiedProtocol());
            }
            throw e.getTargetException();
          }
          throw e;
        }
      }
    } else if (METHOD_PROLOG_PROXY.equals(methodName)) {
      try {
        if (args[0] != null) {
          return listener
              .invoke(method, args, ((ServerPrepareResult) args[0]).getUnProxiedProtocol());
        }
        return null;
      } catch (InvocationTargetException e) {
        if (e.getTargetException() != null) {
          if (e.getTargetException() instanceof SQLException
              && hasToHandleFailover((SQLException) e.getTargetException())) {
            return handleFailOver((SQLException) e.getTargetException(), method, args,
                ((ServerPrepareResult) args[0]).getUnProxiedProtocol());
          }
          throw e.getTargetException();
        }
        throw e;
      }
    } else if (METHOD_RESET.equals(methodName)) {
      //listener will report reset on any active connections (Master/slave)
      listener.reset();
      return null;
    }

    return executeInvocation(method, args, false);

  }

  private Object executeInvocation(Method method, Object[] args, boolean isSecondExecution)
      throws Throwable {

    try {
      return listener.invoke(method, args);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() != null) {
        if (e.getTargetException() instanceof SQLException) {

          SQLException queryException = (SQLException) e.getTargetException();
          Protocol protocol = listener.getCurrentProtocol();

          queryException = addHostInformationToException(queryException, protocol);

          //check that failover is due to kill command
          boolean killCmd = queryException != null
              && queryException.getSQLState() != null
              && queryException.getSQLState().equals("70100")
              && 1927 == queryException.getErrorCode();

          if (killCmd) {
            handleFailOver(queryException, method, args, protocol);
            return null;
          }

          if (hasToHandleFailover(queryException)) {
            return handleFailOver(queryException, method, args, protocol);
          }

          //error is "The MariaDB server is running with the %s option so it cannot execute this statement"
          //checking that server was master has not been demote to slave without resetting connections
          if (queryException.getErrorCode() == 1290
              && !isSecondExecution
              && protocol != null
              && protocol.isMasterConnection()
              && !protocol.checkIfMaster()) {

            boolean inTransaction = protocol.inTransaction();
            boolean isReconnected;

            //connection state has changed, master connection is now read-only
            //reconnect to master, to re-execute command if wasn't in a transaction since
            //we are sure has not been executed.

            //reconnection
            lock.lock();
            try {
              protocol.close();
              isReconnected = listener.primaryFail(null, null, false).isReconnected;
            } finally {
              lock.unlock();
            }

            //relaunch command
            if (isReconnected && !inTransaction) {
              return executeInvocation(method, args, true);
            }

            //throw exception if not reconnected, or was in a transaction
            return handleFailOver(queryException, method, args, listener.getCurrentProtocol());

          }
        }
        throw e.getTargetException();
      }
      throw e;
    }
  }

  /**
   * After a connection exception, launch failover.
   *
   * @param qe     the exception thrown
   * @param method the method to call if failover works well
   * @param args   the arguments of the method
   * @return the object return from the method
   * @throws Throwable throwable
   */
  private Object handleFailOver(SQLException qe, Method method, Object[] args, Protocol protocol)
      throws Throwable {
    HostAddress failHostAddress = null;
    boolean failIsMaster = true;
    if (protocol != null) {
      failHostAddress = protocol.getHostAddress();
      failIsMaster = protocol.isMasterConnection();
    }

    HandleErrorResult handleErrorResult = listener.handleFailover(qe, method, args, protocol);
    if (handleErrorResult.mustThrowError) {
      listener
          .throwFailoverMessage(failHostAddress, failIsMaster, qe, handleErrorResult.isReconnected);
    }
    return handleErrorResult.resultObject;
  }

  /**
   * Check if this Sqlerror is a connection exception. if that's the case, must be handle by
   * failover
   *
   * <p>error codes : 08000 : connection exception 08001 : SQL client unable to establish SQL
   * connection 08002 : connection name in use 08003 : connection does not exist 08004 : SQL server
   * rejected SQL connection 08006 : connection failure 08007 : transaction resolution unknown 70100
   * : connection was killed if error code is "1927"</p>
   *
   * @param exception the Exception
   * @return true if there has been a connection error that must be handled by failover
   */
  public boolean hasToHandleFailover(SQLException exception) {
    return exception.getSQLState() != null
        && (exception.getSQLState().startsWith("08")
        || (exception.getSQLState().equals("70100") && 1927 == exception.getErrorCode()));
  }

  /**
   * Launch reconnect implementation.
   *
   * @throws SQLException exception
   */
  public void reconnect() throws SQLException {
    try {
      listener.reconnect();
    } catch (SQLException e) {
      ExceptionMapper.throwException(e, null, null);
    }
  }

  public Listener getListener() {
    return listener;
  }
}
