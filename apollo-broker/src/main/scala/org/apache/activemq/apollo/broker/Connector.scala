/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker

import org.fusesource.hawtdispatch._
import org.fusesource.hawtdispatch.{Dispatch}
import org.apache.activemq.apollo.dto.{ConnectorDTO}
import protocol.{ProtocolFactory, Protocol}
import org.apache.activemq.apollo.transport._
import org.apache.activemq.apollo.util._
import org.apache.activemq.apollo.util.OptionSupport._


/**
 * <p>
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
object Connector extends Log {
}

trait Connector extends BaseService {

  def broker:Broker
  def id:String
  def stopped(connection:BrokerConnection):Unit
  def config:ConnectorDTO
  def accepted:LongCounter
  def connected:LongCounter
  def update(config: ConnectorDTO, on_complete:Runnable):Unit

}

/**
 * <p>
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class AcceptingConnector(val broker:Broker, val id:String) extends Connector {

  import Connector._

  override val dispatch_queue = broker.dispatch_queue

  var config:ConnectorDTO = new ConnectorDTO
  config.id = id
  config.bind = "tcp://0.0.0.:0"

  var transport_server:TransportServer = _
  var protocol:Protocol = _
  val accepted = new LongCounter()
  val connected = new LongCounter()

  override def toString = "connector: "+config.id

  object BrokerAcceptListener extends TransportAcceptListener {
    def onAcceptError(e: Exception): Unit = {
      warn(e, "Error occured while accepting client connection.")
    }

    def onAccept(transport: Transport): Unit = {
      if( protocol!=null ) {
        transport.setProtocolCodec(protocol.createProtocolCodec)
      }

      accepted.incrementAndGet
      connected.incrementAndGet()
      var connection = new BrokerConnection(AcceptingConnector.this, broker.connection_id_counter.incrementAndGet)
      connection.dispatch_queue.setLabel("connection %d to %s".format(connection.id, transport.getRemoteAddress))
      connection.protocol_handler = protocol.createProtocolHandler
      connection.transport = transport

      broker.init_dispatch_queue(connection.dispatch_queue)

      broker.connections.put(connection.id, connection)
      try {
        connection.start()
      } catch {
        case e1: Exception => {
          onAcceptError(e1)
        }
      }

      if(at_connection_limit) {
        // We stop accepting connections at this point.
        info("Connection limit reached. Clients connected: %d", connected.get)
        transport_server.suspend
      }
    }
  }

  def at_connection_limit = {
    connected.get >= config.connection_limit.getOrElse(Integer.MAX_VALUE)
  }

  /**
   */
  def update(config: ConnectorDTO, on_completed:Runnable) = dispatch_queue {
    if ( !service_state.is_started || this.config == config ) {
      this.config = config
      on_completed.run
    } else {
      // if the connector config is updated.. lets stop, apply config, then restart
      // the connector.
      stop(^{
        this.config = config
        start(on_completed)
      })
    }
  }


  override def _start(on_completed:Runnable) = {
    assert(config!=null, "Connector must be configured before it is started.")

    accepted.set(0)
    connected.set(0)
    protocol = ProtocolFactory.get(config.protocol.getOrElse("any")).get
    transport_server = TransportFactory.bind( config.bind )
    transport_server.setDispatchQueue(dispatch_queue)
    transport_server.setAcceptListener(BrokerAcceptListener)

    if( transport_server.isInstanceOf[KeyAndTrustAware] ) {
      if( broker.key_storage!=null ) {
        transport_server.asInstanceOf[KeyAndTrustAware].setTrustManagers(broker.key_storage.create_trust_managers)
        transport_server.asInstanceOf[KeyAndTrustAware].setKeyManagers(broker.key_storage.create_key_managers)
      } else {
        warn("You are using a transport the expects the broker's key storage to be configured.")
      }
    }
    transport_server.start(^{
      broker.console_log.info("Accepting connections at: "+transport_server.getBoundAddress)
      on_completed.run
    })
  }


  override def _stop(on_completed:Runnable): Unit = {
    transport_server.stop(^{
      broker.console_log.info("Stopped connector at: "+config.bind)
      transport_server = null
      protocol = null
      on_completed.run
    })
  }

  /**
   * Connections callback into the connector when they are stopped so that we can
   * stop tracking them.
   */
  def stopped(connection:BrokerConnection) = dispatch_queue {
    val at_limit = at_connection_limit
    if( broker.connections.remove(connection.id).isDefined ) {
      connected.decrementAndGet()
      if( at_limit ) {
        transport_server.resume
      }
    }
  }

}
