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

package org.wildfly.extension.nosql.subsystem.neo4j;

import static org.wildfly.nosql.common.NoSQLLogger.ROOT_LOGGER;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.operations.validation.ParameterValidator;
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
import org.wildfly.extension.nosql.driver.neo4j.ConfigurationBuilder;
import org.wildfly.extension.nosql.driver.neo4j.Neo4jClientConnectionService;
import org.wildfly.extension.nosql.driver.neo4j.transaction.TransactionEnlistmentType;
import org.wildfly.nosql.common.ConnectionServiceAccess;

/**
 * Neo4jDefinition represents a target database.
 *
 * @author Scott Marlow
 */
public class Neo4jDefinition extends PersistentResourceDefinition {

    private static final List<? extends PersistentResourceDefinition> CHILDREN;

    static {
        List<PersistentResourceDefinition> children = new ArrayList<>();
        children.add(HostDefinition.INSTANCE);
        CHILDREN = Collections.unmodifiableList(children);
    }

    protected static final SimpleAttributeDefinition ID_NAME =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.ID_NAME, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    protected static final SimpleAttributeDefinition JNDI_NAME =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.JNDI_NAME, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(true)
                    .build();

    protected static final SimpleAttributeDefinition MODULE =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.MODULE_NAME, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(false)
                    .build();

    protected static final SimpleAttributeDefinition TRANSACTION =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.TRANSACTION, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(false)
                    .setValidator(new ParameterValidator() {
                        @Override
                        public void validateParameter(String parameterName, ModelNode value) throws OperationFailedException {
                            if (value.isDefined()) {
                                String str = value.asString();
                                if (TransactionEnlistmentType.getFromStringValue(str) == null) {
                                    throw ROOT_LOGGER.invalidParameter(CommonAttributes.TRANSACTION, str, TransactionEnlistmentType.allowedNames());
                                }
                            }
                        }

                        @Override
                        public void validateResolvedParameter(String parameterName, ModelNode value) throws OperationFailedException {
                            validateParameter(parameterName, value.resolve());
                        }

                    }
                    )
                    .build();

    protected static final SimpleAttributeDefinition SECURITY_DOMAIN =
            new SimpleAttributeDefinitionBuilder(CommonAttributes.SECURITY_DOMAIN, ModelType.STRING, true)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setAllowExpression(false)
                    .build();

    protected static List<SimpleAttributeDefinition> ATTRIBUTES = Arrays.asList(
            ID_NAME,
            JNDI_NAME,
            MODULE,
            TRANSACTION,
            SECURITY_DOMAIN);

    static final Map<String, AttributeDefinition> ATTRIBUTES_MAP = new HashMap<>();

    static {
        for (SimpleAttributeDefinition attr : ATTRIBUTES) {
            ATTRIBUTES_MAP.put(attr.getName(), attr);
        }

    }

    static final Neo4jDefinition INSTANCE = new Neo4jDefinition();

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return ATTRIBUTES_MAP.values();
    }

    @Override
    public List<? extends PersistentResourceDefinition> getChildren() {
        return CHILDREN;
    }

    private Neo4jDefinition() {
        super(Neo4jDriverExtension.PROFILE_PATH,
                Neo4jDriverExtension.getResolver(CommonAttributes.PROFILE),
                ProfileAdd.INSTANCE,
                ReloadRequiredRemoveStepHandler.INSTANCE);
    }

    private static class ProfileAdd extends AbstractAddStepHandler {

        private static final ProfileAdd INSTANCE = new ProfileAdd();

        private ProfileAdd() {
            super(ATTRIBUTES);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {

            final ModelNode profileEntry = Resource.Tools.readModel(context.readResource(PathAddress.EMPTY_ADDRESS));
            final Set<String> outboundSocketBindings = new HashSet<>();
            ConfigurationBuilder builder = new ConfigurationBuilder();
            if (profileEntry.hasDefined(CommonAttributes.ID_NAME)) {
                builder.setDescription(profileEntry.get(CommonAttributes.ID_NAME).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.JNDI_NAME)) {
                builder.setJNDIName(profileEntry.get(CommonAttributes.JNDI_NAME).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.MODULE_NAME)) {
                builder.setModuleName(profileEntry.get(CommonAttributes.MODULE_NAME).asString());
            }
            if (profileEntry.hasDefined(CommonAttributes.TRANSACTION)) {
                builder.setTransactionEnlistment(TransactionEnlistmentType.getFromStringValue(profileEntry.get(CommonAttributes.TRANSACTION).asString()));
            }
            if (profileEntry.hasDefined(CommonAttributes.HOST_DEF)) {
                ModelNode hostModels = profileEntry.get(CommonAttributes.HOST_DEF);
                for (ModelNode host : hostModels.asList()) {
                    for (ModelNode hostEntry : host.get(0).asList()) {
                        if (hostEntry.hasDefined(CommonAttributes.OUTBOUND_SOCKET_BINDING_REF)) {
                            String outboundSocketBindingRef = hostEntry.get(CommonAttributes.OUTBOUND_SOCKET_BINDING_REF).asString();
                            outboundSocketBindings.add(outboundSocketBindingRef);
                        }
                    }
                }
            }
            if (profileEntry.hasDefined(CommonAttributes.SECURITY_DOMAIN)) {
                builder.setSecurityDomain(profileEntry.get(CommonAttributes.SECURITY_DOMAIN).asString());
            }
            startNeo4jDriverService(context, builder, outboundSocketBindings);
        }

        private void startNeo4jDriverService(OperationContext context, ConfigurationBuilder builder, final Set<String> outboundSocketBindings) throws OperationFailedException {
            if (builder.getJNDIName() != null && builder.getJNDIName().length() > 0) {
                final Neo4jClientConnectionService neo4jClientConnectionService = new Neo4jClientConnectionService(builder);
                final ServiceName serviceName = ConnectionServiceAccess.serviceName(builder.getDescription());
                final ContextNames.BindInfo bindingInfo = ContextNames.bindInfoFor(builder.getJNDIName());

                final BinderService binderService = new BinderService(bindingInfo.getBindName());
                context.getServiceTarget().addService(bindingInfo.getBinderServiceName(), binderService)
                        .addDependency(Neo4jSubsystemService.serviceName())
                        .addDependency(bindingInfo.getParentContextServiceName(), ServiceBasedNamingStore.class, binderService.getNamingStoreInjector())
                        .addDependency(serviceName, Neo4jClientConnectionService.class, new Injector<Neo4jClientConnectionService>() {
                            @Override
                            public void inject(final Neo4jClientConnectionService value) throws
                                    InjectionException {
                                binderService.getManagedObjectInjector().inject(new ValueManagedReferenceFactory(new ImmediateValue<>(value.getDriver())));
                            }

                            @Override
                            public void uninject() {
                                binderService.getNamingStoreInjector().uninject();
                            }
                        }).install();

                final ServiceBuilder<Neo4jClientConnectionService> serviceBuilder = context.getServiceTarget().addService(serviceName, neo4jClientConnectionService);
                serviceBuilder.addDependency(Neo4jSubsystemService.serviceName(), new CastingInjector<>(neo4jClientConnectionService.getNeo4jSubsystemServiceInjectedValue(), Neo4jSubsystemService.class));
                // add service dependency on each separate hostname/port reference in standalone*.xml referenced from this driver profile definition.
                for (final String outboundSocketBinding : outboundSocketBindings) {
                    final ServiceName outboundSocketBindingDependency = context.getCapabilityServiceName(Neo4jDriverDefinition.OUTBOUND_SOCKET_BINDING_CAPABILITY_NAME, outboundSocketBinding, OutboundSocketBinding.class);
                    serviceBuilder.addDependency(ServiceBuilder.DependencyType.REQUIRED, outboundSocketBindingDependency, OutboundSocketBinding.class, neo4jClientConnectionService.getOutboundSocketBindingInjector(outboundSocketBinding));
                }
                if (builder.getSecurityDomain() != null) {
                    serviceBuilder.addDependency(SubjectFactoryService.SERVICE_NAME, SubjectFactory.class,
                            neo4jClientConnectionService.getSubjectFactoryInjector());
                }

                serviceBuilder.setInitialMode(ServiceController.Mode.ACTIVE).install();
            }
        }
    }

}
