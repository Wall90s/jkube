/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.config.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.eclipse.jkube.kit.common.DebugConstants;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.kit.common.util.KubernetesHelper;
import org.eclipse.jkube.kit.config.service.portforward.PortForwardPodWatcher;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigSpec;

import static org.eclipse.jkube.kit.common.util.KubernetesHelper.extractPodLabelSelector;
import static org.eclipse.jkube.kit.common.util.KubernetesHelper.getName;
import static org.eclipse.jkube.kit.common.util.KubernetesHelper.withSelector;
import static org.eclipse.jkube.kit.common.util.PodHelper.firstContainerHasEnvVars;

public class DebugService {
    public static final String DEBUG_ENV_VARS_UPDATE_MESSAGE = "Updating %s %s with Debug variables in containers";
    private final KitLogger log;
    private final KubernetesClient kubernetesClient;
    private final PortForwardService portForwardService;
    private final ApplyService applyService;
    private String debugSuspendValue;
    private String debugPortInContainer = DebugConstants.ENV_VAR_JAVA_DEBUG_PORT_DEFAULT;

    public DebugService(KitLogger log, KubernetesClient kubernetesClient, PortForwardService portForwardService, ApplyService applyService) {
        this.log = log;
        this.kubernetesClient = kubernetesClient;
        this.portForwardService = portForwardService;
        this.applyService = applyService;
    }

    public void debug(
        String namespace, String fileName, Collection<HasMetadata> entities, String localDebugPort, boolean debugSuspend, KitLogger podWaitLog
    ) {
        if (!isDebugApplicable(entities)) {
            log.error("Unable to proceed with Debug. No application resource found running in the cluster");
            return;
        }
        final NamespacedKubernetesClient nsClient;
        if (namespace != null) {
            nsClient = kubernetesClient.adapt(NamespacedKubernetesClient.class).inNamespace(namespace);
        } else {
            nsClient = kubernetesClient.adapt(NamespacedKubernetesClient.class);
        }
        LabelSelector firstSelector = null;
        for (HasMetadata entity : entities) {
            if (firstSelector == null) {
                firstSelector = extractPodLabelSelector(entity);
            }
            enableDebugging(entity, fileName, debugSuspend);
        }
        if (firstSelector == null) {
            log.error("Debug is not applicable for the currently generated resources");
            return;
        }
        startPortForward(nsClient, firstSelector, debugSuspend, localDebugPort, podWaitLog);
    }

    /**
     * In order to be able to debug, all controller resources should be applied.
     *
     * @param entities list of Kubernetes resources generated by plugin
     * @return boolean value indicating whether debug should be done or not
     */
    private boolean isDebugApplicable(Collection<HasMetadata> entities) {
        boolean controllersApplied = !entities.isEmpty();
        for (HasMetadata h : entities) {
            if (KubernetesHelper.isControllerResource(h)) {
                boolean isApplied = applyService.isAlreadyApplied(h);
                if (!isApplied) {
                    log.warn("%s %s not applied, Did you forget to deploy your application?", h.getKind(), h.getMetadata().getName());
                }
                controllersApplied &= applyService.isAlreadyApplied(h);
            }
        }
        return controllersApplied;
    }

    private void startPortForward(
        NamespacedKubernetesClient nsClient, LabelSelector firstSelector, boolean debugSuspend, String localDebugPort, KitLogger podWaitLog
    ) {
        if (firstSelector != null) {
            Map<String, String> envVars = initDebugEnvVarsMap(debugSuspend);
            String podName = waitForRunningPodWithEnvVar(nsClient, firstSelector, envVars, podWaitLog);
            portForwardService.startPortForward(nsClient, podName, portToInt(debugPortInContainer, "containerDebugPort"), portToInt(localDebugPort, "localDebugPort"));
        }
    }

    // Visible for testing
    Map<String, String> initDebugEnvVarsMap(boolean debugSuspend) {
        Map<String, String> envVars = new TreeMap<>();
        envVars.put(DebugConstants.ENV_VAR_JAVA_DEBUG, "true");
        envVars.put(DebugConstants.ENV_VAR_JAVA_DEBUG_SUSPEND, String.valueOf(debugSuspend));
        if (this.debugSuspendValue != null) {
            envVars.put(DebugConstants.ENV_VAR_JAVA_DEBUG_SESSION, this.debugSuspendValue);
        }
        return envVars;
    }

    // Visible for testing
    void enableDebugging(HasMetadata entity, String fileName, boolean debugSuspend) {
      if (entity instanceof Deployment) {
        enableDebugging((Deployment) entity, fileName, debugSuspend);
      } else if (entity instanceof ReplicaSet) {
        enableDebugging((ReplicaSet) entity, fileName, debugSuspend);
      } else if (entity instanceof ReplicationController) {
        enableDebugging((ReplicationController) entity, fileName, debugSuspend);
      } else if (entity instanceof DeploymentConfig) {
        enableDebugging((DeploymentConfig) entity, fileName, debugSuspend);
      }
    }

    private Consumer<PodTemplateSpec> applyEntity(HasMetadata entity, String fileName) {
      return template -> {
        log.info(DEBUG_ENV_VARS_UPDATE_MESSAGE, entity.getKind(), entity.getMetadata().getName());
        applyService.apply(entity, fileName);
      };
    }

    private Predicate<PodTemplateSpec> enableDebuggingFilterFunc(HasMetadata entity, boolean debugSuspend) {
        return pts -> enableDebugging(entity, pts, debugSuspend);
    }

    private boolean isDebugAlreadyEnabled(ReplicationController entity, boolean debugSuspend) {
      return firstContainerHasEnvVars(
          kubernetesClient.resource(entity).fromServer().get().getSpec().getTemplate().getSpec().getContainers(),
          initDebugEnvVarsMap(debugSuspend));
    }

    private boolean isDebugAlreadyEnabled(DeploymentConfig entity, boolean debugSuspend) {
        return firstContainerHasEnvVars(
            kubernetesClient.resource(entity).fromServer().get().getSpec().getTemplate().getSpec().getContainers(),
            initDebugEnvVarsMap(debugSuspend));
    }

    private void enableDebugging(ReplicationController entity, String fileName, boolean debugSuspend) {
      Optional.ofNullable(entity)
          .map(ReplicationController::getSpec)
          .map(ReplicationControllerSpec::getTemplate)
          .filter(enableDebuggingFilterFunc(entity, debugSuspend))
          .filter(pts -> !isDebugAlreadyEnabled(entity, debugSuspend))
          .ifPresent(applyEntity(entity, fileName));
    }

    private void enableDebugging(ReplicaSet entity, String fileName, boolean debugSuspend) {
      Optional.ofNullable(entity)
          .map(ReplicaSet::getSpec)
          .map(ReplicaSetSpec::getTemplate)
          .filter(enableDebuggingFilterFunc(entity, debugSuspend))
          .ifPresent(applyEntity(entity, fileName));
    }

    private void enableDebugging(Deployment entity, String fileName, boolean debugSuspend) {
      Optional.ofNullable(entity)
          .map(Deployment::getSpec)
          .map(DeploymentSpec::getTemplate)
          .filter(enableDebuggingFilterFunc(entity, debugSuspend))
          .ifPresent(applyEntity(entity, fileName));
    }

    private void enableDebugging(DeploymentConfig entity, String fileName, boolean debugSuspend) {
      Optional.ofNullable(entity)
          .map(DeploymentConfig::getSpec)
          .map(DeploymentConfigSpec::getTemplate)
          .filter(enableDebuggingFilterFunc(entity, debugSuspend))
          .filter(pts -> !isDebugAlreadyEnabled(entity, debugSuspend))
          .ifPresent(applyEntity(entity, fileName));
    }

    private String waitForRunningPodWithEnvVar(NamespacedKubernetesClient nsClient, LabelSelector selector, final Map<String, String> envVars, KitLogger podWaitLog) {
        //  wait for the newest pod to be ready with the given env var
        FilterWatchListDeletable<Pod, PodList, PodResource> pods = withSelector(nsClient.pods(), selector, log);
        PodList list = pods.list();
        if (list != null) {
            Pod latestPod = KubernetesHelper.getNewestPod(list.getItems());
            if (latestPod != null && firstContainerHasEnvVars(latestPod, envVars) && KubernetesHelper.isPodRunning(latestPod)) {
                log.info("Debug Pod ready: %s", latestPod.getMetadata().getName());
                return getName(latestPod);
            }
        }
        PortForwardPodWatcher portForwardPodWatcher = new PortForwardPodWatcher(podWaitLog, envVars);
        log.info("No Active debug pod with provided selector and environment variables found! Waiting for pod to be ready...");
        log.info("Waiting for debug pod with selector " + selector + " and environment variables " + envVars);
        pods.watch(portForwardPodWatcher);

        // now lets wait forever?
        while (portForwardPodWatcher.getPodReadyLatch().getCount() > 0) {
            try {
                portForwardPodWatcher.getPodReadyLatch().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (portForwardPodWatcher.getFoundPod() != null) {
                return getName(portForwardPodWatcher.getFoundPod());
            }
        }
        throw new IllegalStateException("Could not find a running pod with environment variables " + envVars);
    }

    private boolean enableDebugging(HasMetadata entity, PodTemplateSpec template, boolean debugSuspend) {
        if (template != null) {
            PodSpec podSpec = template.getSpec();
            if (podSpec != null) {
                List<Container> containers = podSpec.getContainers();
                boolean enabled = false;
                for (Container container : containers) {
                    enabled |= setDebugEnvVar(container, debugSuspend);
                    enabled |= addDebugContainerPort(container);
                    enabled |= handleDebugSuspendEnvVar(container, debugSuspend, entity);
                }
                if (enabled) {
                    log.info("Enabling debug on " + KubernetesHelper.getKind(entity) + " " + getName(entity));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean addDebugContainerPort(Container container) {
        List<ContainerPort> ports = container.getPorts();
        boolean enabled = false;
        if (ports == null) {
            ports = new ArrayList<>();
        }
        if (!KubernetesHelper.containsPort(ports, debugPortInContainer)) {
            ContainerPort port = KubernetesHelper.addPort(debugPortInContainer, "debug", log);
            if (port != null) {
                ports.add(port);
                container.setPorts(ports);
                enabled = true;
            }
        }
        return enabled;
    }

    private boolean setDebugEnvVar(Container container, boolean debugSuspend) {
        List<EnvVar> env = container.getEnv();
        boolean enabled = false;
        if (env == null) {
            env = new ArrayList<>();
        }
        debugPortInContainer = KubernetesHelper.getEnvVar(env, DebugConstants.ENV_VAR_JAVA_DEBUG_PORT, DebugConstants.ENV_VAR_JAVA_DEBUG_PORT_DEFAULT);
        if (KubernetesHelper.setEnvVar(env, DebugConstants.ENV_VAR_JAVA_DEBUG, "true")) {
            container.setEnv(env);
            enabled = true;
        }
        if (KubernetesHelper.setEnvVar(env, DebugConstants.ENV_VAR_JAVA_DEBUG_SUSPEND, String.valueOf(debugSuspend))) {
            container.setEnv(env);
            enabled = true;
        }
        return enabled;
    }

    private boolean handleDebugSuspendEnvVar(Container container, boolean debugSuspend, HasMetadata entity) {
        List<EnvVar> env = container.getEnv();
        if (debugSuspend) {
            // Setting a random session value to force pod restart
            this.debugSuspendValue = String.valueOf(new SecureRandom().nextLong());
            KubernetesHelper.setEnvVar(env, DebugConstants.ENV_VAR_JAVA_DEBUG_SESSION, this.debugSuspendValue);
            container.setEnv(env);
            if (container.getReadinessProbe() != null) {
                log.info("Readiness probe will be disabled on " + KubernetesHelper.getKind(entity) + " " + getName(entity) + " to allow attaching a remote debugger during suspension");
                container.setReadinessProbe(null);
            }
            if (container.getLivenessProbe() != null) {
                log.info("Liveness probe will be disabled on " + KubernetesHelper.getKind(entity) + " " + getName(entity) + " to allow attaching a remote debugger during suspension");
                container.setLivenessProbe(null);
            }
            return true;
        } else {
            if (KubernetesHelper.removeEnvVar(env, DebugConstants.ENV_VAR_JAVA_DEBUG_SESSION)) {
                container.setEnv(env);
                return true;
            }
        }
        return false;
    }

    private int portToInt(String port, String name) {
        try {
            return Integer.parseInt(port);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid port value: " + name +"=" + port);
        }
    }

}
