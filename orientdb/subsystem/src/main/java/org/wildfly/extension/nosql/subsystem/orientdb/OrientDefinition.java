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

package org.wildfly.extension.nosql.subsystem.orientdb;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.naming.ServiceBasedNamingStore;
import org.jboss.as.naming.ValueManagedReferenceFactory;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.as.security.service.SubjectFactoryService;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.inject.CastingInjector;
import org.jboss.msc.inject.InjectionException;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.value.ImmediateValue;
import org.jboss.security.SubjectFactory;
import org.wildfly.extension.nosql.driver.Configuration;
import org.wildfly.extension.nosql.driver.OrientClientConnectionsService;
import org.wildfly.extension.nosql.driver.OrientInteraction;
import org.wildfly.nosql.common.ConnectionServiceAccess;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
final class OrientDefinition extends PersistentResourceDefinition {

    private static final List<PersistentResourceDefinition> CHILDREN = Collections.singletonList(HostDefinition.INSTANCE);

    private static final SimpleAttributeDefinition ID =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.ID, ModelType.STRING, false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    private static final SimpleAttributeDefinition DATABASE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.DATABASE, ModelType.STRING, false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    private static final SimpleAttributeDefinition REMOTE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.REMOTE, ModelType.BOOLEAN, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    private static final SimpleAttributeDefinition JNDI_NAME =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.JNDI_NAME, ModelType.STRING, false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    protected static final SimpleAttributeDefinition MODULE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.MODULE_NAME, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(false)
                    .build();

    private static final SimpleAttributeDefinition SECURITY_DOMAIN =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.SECURITY_DOMAIN, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    private static final SimpleAttributeDefinition MAX_PARTITION_SIZE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.MAX_PARTITION_SIZE, ModelType.INT, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    private static final SimpleAttributeDefinition MAX_POOL_SIZE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.MAX_POOL_SIZE, ModelType.INT, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    static final AttributeDefinition[] ATTRIBUTES = { ID, DATABASE, REMOTE, JNDI_NAME, MODULE , SECURITY_DOMAIN,
            MAX_PARTITION_SIZE, MAX_POOL_SIZE };

    static final OrientDefinition INSTANCE = new OrientDefinition();

    private OrientDefinition() {
        super(OrientDriverExtension.ORIENT_PATH,
                OrientDriverExtension.getResourceDescriptionResolver(CommonAttributes.ORIENT), OrientAdd.INSTANCE,
                ReloadRequiredRemoveStepHandler.INSTANCE);
    }

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return Arrays.asList(ATTRIBUTES);
    }

    @Override
    public List<? extends PersistentResourceDefinition> getChildren() {
        return CHILDREN;
    }

    private static final class OrientAdd extends AbstractAddStepHandler {

        private static final OrientAdd INSTANCE = new OrientAdd();

        private OrientAdd() {
            super(ATTRIBUTES);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model)
                throws OperationFailedException {
            ModelNode profileEntry = Resource.Tools.readModel(context.readResource(PathAddress.EMPTY_ADDRESS));
            Configuration configuration = getConfiguration(profileEntry);
            String outboundSocketBinding = getOutboundSocketBinding(profileEntry);

            startServices(context, configuration, outboundSocketBinding);
        }

        private void startServices(OperationContext context, Configuration configuration, String outboundSocketBinding) {
            ServiceName connectionsServiceName = ConnectionServiceAccess.serviceName(configuration.getProfileName());
            OrientInteraction orientInteraction = new OrientInteraction(configuration);
            OrientClientConnectionsService connectionsService = new OrientClientConnectionsService(configuration, orientInteraction);
            ServiceBuilder<OrientClientConnectionsService> connectionsServiceBuilder = context.getServiceTarget()
                    .addService(connectionsServiceName, connectionsService);

            connectionsServiceBuilder.addDependency(OrientSubsystemService.SERVICE_NAME, new CastingInjector<>(
                    connectionsService.getOrientSubsystemServiceInjectedValue(), OrientSubsystemService.class));

            ServiceName outboundSocketBindingServiceName = context.getCapabilityServiceName(
                    OrientDriverDefinition.OUTBOUND_SOCKET_BINDING_CAPABILITY_NAME, outboundSocketBinding,
                    OutboundSocketBinding.class);
            connectionsServiceBuilder.addDependency(ServiceBuilder.DependencyType.REQUIRED, outboundSocketBindingServiceName,
                    new CastingInjector<>(connectionsService.getOutboundSocketBindingInjectedValue(),
                            OutboundSocketBinding.class));
            if (configuration.getSecurityDomain() != null) {
                connectionsServiceBuilder.addDependency(SubjectFactoryService.SERVICE_NAME, SubjectFactory.class,
                        connectionsService.getSubjectFactoryInjector());
            }

            connectionsServiceBuilder.setInitialMode(ServiceController.Mode.ACTIVE).install();
            bindJndi(context, connectionsServiceName, configuration.getJndiName(), orientInteraction.getDatabasePoolClass());
        }

        private <T> void bindJndi(OperationContext context, ServiceName serviceName, String jndiName, Class<T> clazz) {
            ContextNames.BindInfo bindInfo = ContextNames.bindInfoFor(jndiName);
            BinderService binderService = new BinderService(bindInfo.getBindName());
            context.getServiceTarget().addService(bindInfo.getBinderServiceName(), binderService)
                    .addDependency(OrientSubsystemService.SERVICE_NAME)
                    .addDependency(bindInfo.getParentContextServiceName(), ServiceBasedNamingStore.class,
                            binderService.getNamingStoreInjector())
                    .addDependency(serviceName, OrientClientConnectionsService.class,
                            new Injector<OrientClientConnectionsService>() {
                                @Override
                                public void inject(final OrientClientConnectionsService value) throws InjectionException {
                                    binderService.getManagedObjectInjector().inject(
                                            new ValueManagedReferenceFactory(new ImmediateValue<>(value.unwrap(clazz))));
                                }

                                @Override
                                public void uninject() {
                                    binderService.getNamingStoreInjector().uninject();
                                }
                            })
                    .install();
        }

        private Configuration getConfiguration(ModelNode profileEntry) {
            Configuration.Builder builder = new Configuration.Builder();

            if (profileEntry.hasDefined(CommonAttributes.ID)) {
                builder.profileName(profileEntry.get(CommonAttributes.ID).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.DATABASE)) {
                builder.database(profileEntry.get(CommonAttributes.DATABASE).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.REMOTE)) {
                builder.remote(profileEntry.get(CommonAttributes.REMOTE).asBoolean());
            }
            if (profileEntry.hasDefined(CommonAttributes.JNDI_NAME)) {
                builder.jndiName(profileEntry.get(CommonAttributes.JNDI_NAME).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.MODULE_NAME)) {
                builder.moduleName(profileEntry.get(CommonAttributes.MODULE_NAME).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.SECURITY_DOMAIN)) {
                builder.securityDomain(profileEntry.get(CommonAttributes.SECURITY_DOMAIN).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.MAX_PARTITION_SIZE)) {
                builder.maxPartitionSize(profileEntry.get(CommonAttributes.MAX_PARTITION_SIZE).asInt());
            }
            if (profileEntry.hasDefined(CommonAttributes.MAX_POOL_SIZE)) {
                builder.maxPoolSize(profileEntry.get(CommonAttributes.MAX_POOL_SIZE).asInt());
            }

            return builder.build();
        }

        private String getOutboundSocketBinding(ModelNode profileEntry) {
            if (profileEntry.hasDefined(CommonAttributes.HOST)) {
                ModelNode hostModels = profileEntry.get(CommonAttributes.HOST);
                for (ModelNode host : hostModels.asList()) {
                    for (ModelNode hostEntry : host.get(0).asList()) {
                        if (hostEntry.hasDefined(CommonAttributes.OUTBOUND_SOCKET_BINDING_REF)) {
                            return hostEntry.get(CommonAttributes.OUTBOUND_SOCKET_BINDING_REF).asString();
                        }
                    }
                }
            }

            return null;
        }

    }

}
