/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.extension.nosql.driver.cassandra;

import static org.wildfly.nosql.common.NoSQLLogger.ROOT_LOGGER;

import java.util.HashMap;
import java.util.Map;

import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.inject.MapInjector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.security.SubjectFactory;
import org.wildfly.extension.nosql.subsystem.cassandra.CassandraSubsystemService;
import org.wildfly.nosql.common.spi.NoSQLConnection;

/**
 * CassandraDriverService represents the connection into Cassandra
 *
 * @author Scott Marlow
 */
public class CassandraClientConnectionsService implements Service<CassandraClientConnectionsService>, NoSQLConnection {

    private final ConfigurationBuilder configurationBuilder;
    // standard application server way to obtain target hostname + port for target NoSQL database server(s)
    private Map<String, OutboundSocketBinding> outboundSocketBindings = new HashMap<String, OutboundSocketBinding>();
    private final CassandraInteraction cassandraInteraction;
    private final Class clusterClass;
    private final Class sessionClass;
    private Object cluster;  // represents connection into Cassandra
    private Object session;  // only set if keyspaceName is specified
    private final InjectedValue<CassandraSubsystemService> cassandraSubsystemServiceInjectedValue = new InjectedValue<>();
    private final InjectedValue<SubjectFactory> subjectFactory = new InjectedValue<>();

    public CassandraClientConnectionsService(ConfigurationBuilder configurationBuilder) {
        this.configurationBuilder = configurationBuilder;
        cassandraInteraction = new CassandraInteraction(configurationBuilder);
        clusterClass = cassandraInteraction.getClusterClass();
        sessionClass = cassandraInteraction.getSessionClass();
    }

    public InjectedValue<SubjectFactory> getSubjectFactoryInjector() {
        return subjectFactory;
    }

    public Injector<OutboundSocketBinding> getOutboundSocketBindingInjector(String name) {
        return new MapInjector<String, OutboundSocketBinding>(outboundSocketBindings, name);
    }

    public InjectedValue<CassandraSubsystemService> getCassandraSubsystemServiceInjectedValue() {
        return cassandraSubsystemServiceInjectedValue;
    }

    @Override
    public void start(StartContext startContext) throws StartException {

        try {
            // maintain a mapping from JNDI name to NoSQL module name, that we will use during deployment time to
            // identify the static module name to add to the deployment.
            cassandraSubsystemServiceInjectedValue.getValue().addModuleNameFromJndi(configurationBuilder.getJNDIName(), configurationBuilder.getModuleName());
            cassandraSubsystemServiceInjectedValue.getValue().addModuleNameFromProfile(configurationBuilder.getDescription(), configurationBuilder.getModuleName());

            for (OutboundSocketBinding target : outboundSocketBindings.values()) {
                if (target.getDestinationPort() > 0) {
                    cassandraInteraction.withPort(target.getDestinationPort());
                }
                if (target.getUnresolvedDestinationAddress() != null) {
                    cassandraInteraction.addContactPoint(target.getUnresolvedDestinationAddress());
                }
            }

            if (subjectFactory.getOptionalValue() != null) {
                cassandraInteraction.subjectFactory(subjectFactory.getOptionalValue());
            }

            if (configurationBuilder.getDescription() != null) {
                cassandraInteraction.withClusterName(configurationBuilder.getDescription());
            }

            if (configurationBuilder.isWithSSL()) {
                cassandraInteraction.withSSL();
            }

            cluster = cassandraInteraction.build();

            String keySpace = configurationBuilder.getKeySpace();
            if (keySpace != null) {
                session = cassandraInteraction.connect(cluster, keySpace);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("could not setup Cassandra connection " + configurationBuilder.getDescription(), throwable);
        }
    }

    @Override
    public void stop(StopContext stopContext) {
        try {
            cassandraSubsystemServiceInjectedValue.getValue().removeModuleNameFromJndi(configurationBuilder.getJNDIName());
            cassandraSubsystemServiceInjectedValue.getValue().removeModuleNameFromProfile(configurationBuilder.getDescription());
            if (session != null) {
                cassandraInteraction.sessionClose(session);
                session = null;
            }
            cassandraInteraction.clusterClose(cluster);
            cluster = null;
        } catch (Throwable throwable) {
            ROOT_LOGGER.driverFailedToStop(throwable);
        }
    }

    @Override
    public CassandraClientConnectionsService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public Object getCluster() {
        return cluster;
    }

    public Object getSession() {
        return session;
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if ( clusterClass.isAssignableFrom( clazz ) ) {
            return (T) cluster;
        }
        if ( sessionClass.isAssignableFrom( clazz)) {
            return (T) session;
        }
        throw ROOT_LOGGER.unassignable(clazz);
    }


}
