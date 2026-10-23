package ar.changeo.config;

import ar.changeo.security.PrivateHttpsOrigin;
import ar.changeo.security.PrivateHttpsOriginFilter;
import jakarta.servlet.DispatcherType;
import java.net.InetAddress;
import java.util.EnumSet;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.filters.RemoteIpFilter;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.SSLValve;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(PrivateHttpsConfiguration.OriginPresent.class)
public class PrivateHttpsConfiguration {
    private static final String INVALID_CONFIGURATION = "Invalid private HTTPS configuration";

    @Bean
    PrivateHttpsOrigin privateHttpsOrigin(Environment environment) {
        try {
            return PrivateHttpsOrigin.parse(environment.getProperty("changeo.private-https.origin"));
        } catch (RuntimeException failure) {
            throw invalidConfiguration();
        }
    }

    @Bean
    FilterRegistrationBean<PrivateHttpsOriginFilter> privateHttpsOriginFilter(PrivateHttpsOrigin origin) {
        var registration = new FilterRegistrationBean<>(new PrivateHttpsOriginFilter(origin));
        registration.setOrder(-120);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC));
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> privateHttpsServer(
            PrivateHttpsOrigin origin, Environment environment, ListableBeanFactory beans) {
        return new DirectTlsCustomizer(origin, environment, beans);
    }

    static final class OriginPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().containsProperty("changeo.private-https.origin");
        }
    }

    private record DirectTlsCustomizer(PrivateHttpsOrigin origin, Environment environment, ListableBeanFactory beans)
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
        @Override
        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addConnectorCustomizers(connector -> connector.addLifecycleListener(event -> {
                if (Lifecycle.BEFORE_INIT_EVENT.equals(event.getType())) {
                    validateFactory(factory);
                    validateConnector(connector);
                }
            }));
            factory.addContextCustomizers(context -> {
                var reports = new ErrorReportValve();
                reports.setShowReport(false);
                reports.setShowServerInfo(false);
                context.getParent().getPipeline().addValve(reports);
                context.addLifecycleListener(event -> {
                    if (Lifecycle.START_EVENT.equals(event.getType())) {
                        validateFactory(factory);
                        validatePipeline(context);
                    }
                });
            });
        }

        private void validateFactory(TomcatServletWebServerFactory factory) {
            String forwarding = environment.getProperty("server.forward-headers-strategy", "none");
            if (factory.getSsl() == null || !factory.getSsl().isEnabled()
                    || factory.getPort() != origin.port() || !matchesAddress(factory.getAddress())
                    || !factory.getSettings().getContextPath().toString().isEmpty()
                    || !factory.getAdditionalConnectors().isEmpty() || !"none".equalsIgnoreCase(forwarding)
                    || beans.getBeanNamesForType(ForwardedHeaderFilter.class, false, false).length != 0) {
                throw invalidConfiguration();
            }
        }

        private void validateConnector(Connector connector) {
            if (!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol)
                    || !protocol.isSSLEnabled() || !connector.getSecure() || !"https".equals(connector.getScheme())
                    || connector.getPortWithOffset() != origin.port() || !matchesAddress(protocol.getAddress())
                    || connector.getProxyName() != null || connector.getProxyPort() != 0
                    || connector.getService().findConnectors().length != 1) {
                throw invalidConfiguration();
            }
        }

        private boolean matchesAddress(InetAddress address) {
            if (address == null) { return false; }
            byte[] bytes = address.getAddress();
            if (origin.host().equals("127.0.0.1")) {
                return bytes.length == 4 && bytes[0] == 127 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 1;
            }
            if (bytes.length != 16 || bytes[15] != 1) { return false; }
            for (int index = 0; index < 15; index++) {
                if (bytes[index] != 0) { return false; }
            }
            return true;
        }

        private void validatePipeline(Context context) {
            if (!context.getPath().isEmpty()) { throw invalidConfiguration(); }
            for (var definition : context.findFilterDefs()) {
                if (definition.getFilter() instanceof ForwardedHeaderFilter
                        || definition.getFilter() instanceof RemoteIpFilter
                        || ForwardedHeaderFilter.class.getName().equals(definition.getFilterClass())
                        || RemoteIpFilter.class.getName().equals(definition.getFilterClass())) {
                    throw invalidConfiguration();
                }
            }
            for (Container container = context; container != null; container = container.getParent()) {
                for (var valve : container.getPipeline().getValves()) {
                    if (valve instanceof RemoteIpValve || valve instanceof SSLValve) { throw invalidConfiguration(); }
                    if (valve instanceof ErrorReportValve reports) {
                        reports.setShowReport(false);
                        reports.setShowServerInfo(false);
                    }
                }
            }
        }
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException(INVALID_CONFIGURATION);
    }
}
