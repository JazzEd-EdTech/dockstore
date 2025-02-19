/*
 *    Copyright 2017 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.webservice;

import static javax.servlet.DispatcherType.REQUEST;
import static org.eclipse.jetty.servlets.CrossOriginFilter.ACCESS_CONTROL_ALLOW_METHODS_HEADER;
import static org.eclipse.jetty.servlets.CrossOriginFilter.ALLOWED_HEADERS_PARAM;
import static org.eclipse.jetty.servlets.CrossOriginFilter.ALLOWED_METHODS_PARAM;
import static org.eclipse.jetty.servlets.CrossOriginFilter.ALLOWED_ORIGINS_PARAM;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import io.dockstore.common.LanguagePluginManager;
import io.dockstore.language.CompleteLanguageInterface;
import io.dockstore.language.MinimalLanguageInterface;
import io.dockstore.language.RecommendedLanguageInterface;
import io.dockstore.webservice.core.AppTool;
import io.dockstore.webservice.core.Author;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Category;
import io.dockstore.webservice.core.Checksum;
import io.dockstore.webservice.core.CloudInstance;
import io.dockstore.webservice.core.Collection;
import io.dockstore.webservice.core.CollectionOrganization;
import io.dockstore.webservice.core.DeletedUsername;
import io.dockstore.webservice.core.EntryVersion;
import io.dockstore.webservice.core.Event;
import io.dockstore.webservice.core.FileFormat;
import io.dockstore.webservice.core.Image;
import io.dockstore.webservice.core.Label;
import io.dockstore.webservice.core.LambdaEvent;
import io.dockstore.webservice.core.Notification;
import io.dockstore.webservice.core.OrcidAuthor;
import io.dockstore.webservice.core.Organization;
import io.dockstore.webservice.core.OrganizationUser;
import io.dockstore.webservice.core.ParsedInformation;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Validation;
import io.dockstore.webservice.core.VersionMetadata;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.doi.DOIGeneratorFactory;
import io.dockstore.webservice.helpers.CacheConfigManager;
import io.dockstore.webservice.helpers.ConstraintExceptionMapper;
import io.dockstore.webservice.helpers.ElasticSearchHelper;
import io.dockstore.webservice.helpers.GoogleHelper;
import io.dockstore.webservice.helpers.MetadataResourceHelper;
import io.dockstore.webservice.helpers.ORCIDHelper;
import io.dockstore.webservice.helpers.PersistenceExceptionMapper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.TransactionExceptionMapper;
import io.dockstore.webservice.helpers.statelisteners.PopulateEntryListener;
import io.dockstore.webservice.jdbi.AppToolDAO;
import io.dockstore.webservice.jdbi.BioWorkflowDAO;
import io.dockstore.webservice.jdbi.DeletedUsernameDAO;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.ServiceDAO;
import io.dockstore.webservice.jdbi.TagDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.VersionDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.languages.LanguageHandlerFactory;
import io.dockstore.webservice.permissions.PermissionsFactory;
import io.dockstore.webservice.permissions.PermissionsInterface;
import io.dockstore.webservice.resources.AdminPrivilegesFilter;
import io.dockstore.webservice.resources.AliasResource;
import io.dockstore.webservice.resources.CategoryResource;
import io.dockstore.webservice.resources.CloudInstanceResource;
import io.dockstore.webservice.resources.CollectionResource;
import io.dockstore.webservice.resources.DockerRepoResource;
import io.dockstore.webservice.resources.DockerRepoTagResource;
import io.dockstore.webservice.resources.ElasticSearchHealthCheck;
import io.dockstore.webservice.resources.EntryResource;
import io.dockstore.webservice.resources.EventResource;
import io.dockstore.webservice.resources.HostedToolResource;
import io.dockstore.webservice.resources.HostedWorkflowResource;
import io.dockstore.webservice.resources.LambdaEventResource;
import io.dockstore.webservice.resources.MetadataResource;
import io.dockstore.webservice.resources.NotificationResource;
import io.dockstore.webservice.resources.OrganizationResource;
import io.dockstore.webservice.resources.ServiceResource;
import io.dockstore.webservice.resources.TemplateHealthCheck;
import io.dockstore.webservice.resources.TokenResource;
import io.dockstore.webservice.resources.ToolTesterResource;
import io.dockstore.webservice.resources.UserResource;
import io.dockstore.webservice.resources.UserResourceDockerRegistries;
import io.dockstore.webservice.resources.UsernameRenameRequiredFilter;
import io.dockstore.webservice.resources.WorkflowResource;
import io.dockstore.webservice.resources.proposedGA4GH.ToolsApiExtendedServiceImpl;
import io.dockstore.webservice.resources.proposedGA4GH.ToolsExtendedApi;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.CachingAuthenticator;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.forms.MultiPartBundle;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWorkAwareProxyFactory;
import io.dropwizard.jersey.jackson.JsonProcessingExceptionMapper;
import io.dropwizard.migrations.MigrationsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.openapi.api.impl.ToolsApiServiceImpl;
import io.swagger.api.MetadataApi;
import io.swagger.api.MetadataApiV1;
import io.swagger.api.ToolClassesApi;
import io.swagger.api.ToolClassesApiV1;
import io.swagger.api.ToolsApi;
import io.swagger.api.ToolsApiV1;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import io.swagger.v3.jaxrs2.integration.resources.BaseOpenApiResource;
import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.HttpClient;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.kohsuke.github.extras.okhttp3.ObsoleteUrlFactory;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author dyuen
 */
public class DockstoreWebserviceApplication extends Application<DockstoreWebserviceConfiguration> {
    public static final String GA4GH_API_PATH_V2_BETA = "/api/ga4gh/v2";
    public static final String GA4GH_API_PATH_V2_FINAL = "/ga4gh/trs/v2";
    public static final String GA4GH_API_PATH_V1 = "/api/ga4gh/v1";
    public static final String DOCKSTORE_WEB_CACHE = "/tmp/dockstore-web-cache";
    public static final String DOCKSTORE_WEB_CACHE_MISS_LOG_FILE = "/tmp/dockstore-web-cache.misses.log";
    public static final File CACHE_MISS_LOG_FILE = new File(DOCKSTORE_WEB_CACHE_MISS_LOG_FILE);

    private static OkHttpClient okHttpClient = null;
    private static final Logger LOG = LoggerFactory.getLogger(DockstoreWebserviceApplication.class);
    private static final int BYTES_IN_KILOBYTE = 1024;
    private static final int KILOBYTES_IN_MEGABYTE = 1024;
    private static final int CACHE_IN_MB = 100;
    private static Cache cache = null;

    static {
        // https://ucsc-cgl.atlassian.net/browse/SEAB-3122, see org.jboss.logging.LoggerProviders.java:29
        System.setProperty("org.jboss.logging.provider", "slf4j");
    }

    private final HibernateBundle<DockstoreWebserviceConfiguration> hibernate = new HibernateBundle<>(Token.class, Tool.class, User.class,
            Tag.class, Label.class, SourceFile.class, Workflow.class, CollectionOrganization.class, WorkflowVersion.class, FileFormat.class,
            Organization.class, Notification.class, OrganizationUser.class, Event.class, Collection.class, Validation.class, BioWorkflow.class, Service.class, VersionMetadata.class, Image.class, Checksum.class, LambdaEvent.class,
            ParsedInformation.class, EntryVersion.class, DeletedUsername.class, CloudInstance.class, Author.class, OrcidAuthor.class,
            AppTool.class, Category.class, FullWorkflowPath.class) {
        @Override
        public DataSourceFactory getDataSourceFactory(DockstoreWebserviceConfiguration configuration) {
            return configuration.getDataSourceFactory();
        }
    };

    public static void main(String[] args) throws Exception {
        new DockstoreWebserviceApplication().run(args);
    }

    public static Cache getCache(String cacheNamespace) {
        if (cacheNamespace == null) {
            return cache;
        } else {
            return generateCache(cacheNamespace);
        }
    }

    public static OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    @Override
    public String getName() {
        return "webservice";
    }

    @Override
    public void initialize(Bootstrap<DockstoreWebserviceConfiguration> bootstrap) {

        configureMapper(bootstrap.getObjectMapper());

        // setup hibernate+postgres
        bootstrap.addBundle(hibernate);

        // serve static html as well
        bootstrap.addBundle(new AssetsBundle("/assets/", "/static/"));

        // for database migrations.xml
        bootstrap.addBundle(new MigrationsBundle<>() {
            @Override
            public DataSourceFactory getDataSourceFactory(DockstoreWebserviceConfiguration configuration) {
                return configuration.getDataSourceFactory();
            }
        });

        bootstrap.addBundle(new MultiPartBundle());

        if (cache == null) {
            cache = generateCache(null);
        }
        try {
            cache.initialize();
        } catch (IOException e) {
            LOG.error("Could not create web cache, initialization exception", e);
            throw new RuntimeException(e);
        }
        // match HttpURLConnection which does not have a timeout by default
        OkHttpClient.Builder builder = new OkHttpClient().newBuilder();
        if (System.getenv("CIRCLE_SHA1") != null) {
            builder.eventListener(new CacheHitListener(DockstoreWebserviceApplication.class.getSimpleName(), "central"));
        }
        okHttpClient = builder.cache(cache).connectTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS).build();
        try {
            // this can only be called once per JVM, a factory exception is thrown in our tests
            URL.setURLStreamHandlerFactory(new ObsoleteUrlFactory(okHttpClient));
        } catch (Error factoryException) {
            if (factoryException.getMessage().contains("factory already defined")) {
                LOG.debug("OkHttpClient already registered, skipping");
            } else {
                LOG.error("Could not create web cache, factory exception", factoryException);
                throw new RuntimeException(factoryException);
            }
        }
    }

    private static Cache generateCache(String suffix) {
        int cacheSize = CACHE_IN_MB * BYTES_IN_KILOBYTE * KILOBYTES_IN_MEGABYTE; // 100 MiB
        final File cacheDir;
        try {
            // let's try using the same cache each time
            // not sure how corruptible/non-curruptable the cache is
            // namespace cache when testing on circle ci
            cacheDir = Files.createDirectories(Paths.get(DOCKSTORE_WEB_CACHE + (suffix == null ? "" : "/" + suffix))).toFile();
        } catch (IOException e) {
            LOG.error("Could not create or re-use web cache", e);
            throw new RuntimeException(e);
        }
        return new Cache(cacheDir, cacheSize);
    }

    private static void configureMapper(ObjectMapper objectMapper) {
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.registerModule(new Hibernate5Module());
        objectMapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        // doesn't seem to work, when it does, we could avoid overriding pojo.mustache in swagger
        objectMapper.enable(MapperFeature.ALLOW_EXPLICIT_PROPERTY_RENAMING);
        objectMapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        // To convert every Date we have to RFC 3339, we can use this
        // objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssz"));
    }

    public static File getFilePluginLocation(DockstoreWebserviceConfiguration configuration) {
        String userHome = System.getProperty("user.home");
        String filename = userHome + File.separator + ".dockstore" + File.separator + "language-plugins";
        filename = StringUtils.isEmpty(configuration.getLanguagePluginLocation()) ? filename : configuration.getLanguagePluginLocation();
        LOG.info("File plugin path set to:" + filename);
        return new File(filename);
    }

    @Override
    public void run(DockstoreWebserviceConfiguration configuration, Environment environment) {
        BeanConfig beanConfig = new BeanConfig();
        beanConfig.setSchemes(new String[] { configuration.getExternalConfig().getScheme() });
        String portFragment = configuration.getExternalConfig().getPort() == null ? "" : ":" + configuration.getExternalConfig().getPort();
        beanConfig.setHost(configuration.getExternalConfig().getHostname() + portFragment);
        beanConfig.setBasePath(MoreObjects.firstNonNull(configuration.getExternalConfig().getBasePath(), "/"));
        beanConfig.setResourcePackage("io.dockstore.webservice.resources,io.swagger.api,io.openapi.api");
        beanConfig.setScan(true);

        final DefaultPluginManager languagePluginManager = LanguagePluginManager.getInstance(getFilePluginLocation(configuration));
        describeAvailableLanguagePlugins(languagePluginManager);
        LanguageHandlerFactory.setLanguagePluginManager(languagePluginManager);

        final PublicStateManager publicStateManager = PublicStateManager.getInstance();
        publicStateManager.reset();
        publicStateManager.setConfig(configuration);

        environment.jersey().property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true);
        environment.jersey().register(new JsonProcessingExceptionMapper(true));

        final TemplateHealthCheck healthCheck = new TemplateHealthCheck(configuration.getTemplate());
        environment.healthChecks().register("template", healthCheck);

        final ElasticSearchHealthCheck elasticSearchHealthCheck = new ElasticSearchHealthCheck(new ToolsExtendedApi());
        environment.healthChecks().register("elasticSearch", elasticSearchHealthCheck);
        environment.lifecycle().manage(new ElasticSearchHelper(configuration.getEsConfiguration()));
        final UserDAO userDAO = new UserDAO(hibernate.getSessionFactory());
        final TokenDAO tokenDAO = new TokenDAO(hibernate.getSessionFactory());
        final DeletedUsernameDAO deletedUsernameDAO = new DeletedUsernameDAO(hibernate.getSessionFactory());
        final ToolDAO toolDAO = new ToolDAO(hibernate.getSessionFactory());
        final ServiceDAO serviceDAO = new ServiceDAO(hibernate.getSessionFactory());
        final FileDAO fileDAO = new FileDAO(hibernate.getSessionFactory());
        final WorkflowDAO workflowDAO = new WorkflowDAO(hibernate.getSessionFactory());
        final AppToolDAO appToolDAO = new AppToolDAO(hibernate.getSessionFactory());
        final TagDAO tagDAO = new TagDAO(hibernate.getSessionFactory());
        final EventDAO eventDAO = new EventDAO(hibernate.getSessionFactory());
        final VersionDAO versionDAO = new VersionDAO(hibernate.getSessionFactory());
        final BioWorkflowDAO bioWorkflowDAO = new BioWorkflowDAO(hibernate.getSessionFactory());

        publicStateManager.insertListener(new PopulateEntryListener(toolDAO), publicStateManager.getElasticListener());

        LOG.info("Cache directory for OkHttp is: " + cache.directory().getAbsolutePath());
        LOG.info("This is our custom logger saying that we're about to load authenticators");
        // setup authentication to allow session access in authenticators, see https://github.com/dropwizard/dropwizard/pull/1361
        SimpleAuthenticator authenticator = new UnitOfWorkAwareProxyFactory(getHibernate())
                .create(SimpleAuthenticator.class, new Class[] { TokenDAO.class, UserDAO.class }, new Object[] { tokenDAO, userDAO });
        CachingAuthenticator<String, User> cachingAuthenticator = new CachingAuthenticator<>(environment.metrics(), authenticator,
                configuration.getAuthenticationCachePolicy());
        environment.jersey().register(new AuthDynamicFeature(
                new OAuthCredentialAuthFilter.Builder<User>().setAuthenticator(cachingAuthenticator).setAuthorizer(new SimpleAuthorizer())
                        .setPrefix("Bearer").setRealm("Dockstore User Authentication").buildAuthFilter()));
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));
        environment.jersey().register(RolesAllowedDynamicFeature.class);
        environment.jersey().register(new ConstraintExceptionMapper());


        final HttpClient httpClient = new HttpClientBuilder(environment).using(configuration.getHttpClientConfiguration()).build(getName());

        final PermissionsInterface authorizer = PermissionsFactory.createAuthorizer(tokenDAO, configuration);

        final EntryResource entryResource = new EntryResource(hibernate.getSessionFactory(), authorizer, tokenDAO, toolDAO, versionDAO, userDAO, configuration);
        environment.jersey().register(entryResource);

        final WorkflowResource workflowResource = new WorkflowResource(httpClient, hibernate.getSessionFactory(), authorizer, entryResource, configuration);
        environment.jersey().register(workflowResource);
        final ServiceResource serviceResource = new ServiceResource(httpClient, hibernate.getSessionFactory(), entryResource, configuration);
        environment.jersey().register(serviceResource);

        // Note workflow resource must be passed to the docker repo resource, as the workflow resource refresh must be called for checker workflows
        final DockerRepoResource dockerRepoResource = new DockerRepoResource(httpClient, hibernate.getSessionFactory(), configuration, workflowResource, entryResource);

        environment.jersey().register(dockerRepoResource);
        environment.jersey().register(new DockerRepoTagResource(toolDAO, tagDAO, eventDAO, versionDAO));
        environment.jersey().register(new TokenResource(tokenDAO, userDAO, deletedUsernameDAO, httpClient, cachingAuthenticator, configuration));

        environment.jersey().register(new UserResource(httpClient, getHibernate().getSessionFactory(), workflowResource, dockerRepoResource, cachingAuthenticator, authorizer, configuration));

        MetadataResourceHelper.init(configuration);
        ORCIDHelper.init(configuration);
        environment.jersey().register(new UserResourceDockerRegistries(getHibernate().getSessionFactory()));
        environment.jersey().register(new MetadataResource(getHibernate().getSessionFactory(), configuration));
        environment.jersey().register(new HostedToolResource(getHibernate().getSessionFactory(), authorizer, configuration.getLimitConfig()));
        environment.jersey().register(new HostedWorkflowResource(getHibernate().getSessionFactory(), authorizer, configuration.getLimitConfig()));
        environment.jersey().register(new OrganizationResource(getHibernate().getSessionFactory()));
        environment.jersey().register(new LambdaEventResource(getHibernate().getSessionFactory()));
        environment.jersey().register(new NotificationResource(getHibernate().getSessionFactory()));
        environment.jersey().register(new CollectionResource(getHibernate().getSessionFactory()));
        environment.jersey().register(new EventResource(eventDAO, userDAO));
        environment.jersey().register(new ToolTesterResource(configuration));
        environment.jersey().register(new CloudInstanceResource(getHibernate().getSessionFactory()));
        environment.jersey().register(new CategoryResource(getHibernate().getSessionFactory()));

        // disable odd extra endpoints showing up
        final SwaggerConfiguration swaggerConfiguration = new SwaggerConfiguration().prettyPrint(true);
        swaggerConfiguration.setIgnoredRoutes(Lists.newArrayList("/application.wadl", "/pprof"));
        BaseOpenApiResource openApiResource = new OpenApiResource().openApiConfiguration(swaggerConfiguration);
        environment.jersey().register(openApiResource);

        final AliasResource aliasResource = new AliasResource(hibernate.getSessionFactory(), workflowResource);
        environment.jersey().register(aliasResource);

        // attach the container dao statically to avoid too much modification of generated code
        ToolsApiServiceImpl.setToolDAO(toolDAO);
        ToolsApiServiceImpl.setWorkflowDAO(workflowDAO);
        ToolsApiServiceImpl.setBioWorkflowDAO(bioWorkflowDAO);
        ToolsApiServiceImpl.setServiceDAO(serviceDAO);
        ToolsApiServiceImpl.setAppToolDAO(appToolDAO);
        ToolsApiServiceImpl.setFileDAO(fileDAO);
        ToolsApiServiceImpl.setVersionDAO(versionDAO);
        ToolsApiServiceImpl.setConfig(configuration);
        ToolsApiServiceImpl.setAuthorizer(authorizer);

        ToolsApiExtendedServiceImpl.setStateManager(publicStateManager);
        ToolsApiExtendedServiceImpl.setToolDAO(toolDAO);
        ToolsApiExtendedServiceImpl.setWorkflowDAO(workflowDAO);
        ToolsApiExtendedServiceImpl.setAppToolDAO(appToolDAO);
        ToolsApiExtendedServiceImpl.setConfig(configuration);

        DOIGeneratorFactory.setConfig(configuration);

        GoogleHelper.setConfig(configuration);

        registerAPIsAndMisc(environment);

        // optional CORS support
        // Enable CORS headers
        // final FilterRegistration.Dynamic cors = environment.servlets().addFilter("CORS", CrossOriginFilter.class);
        final FilterHolder filterHolder = environment.getApplicationContext().addFilter(CrossOriginFilter.class, "/*", EnumSet.of(REQUEST));

        filterHolder.setInitParameter(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "GET,POST,DELETE,PUT,OPTIONS,PATCH");
        filterHolder.setInitParameter(ALLOWED_ORIGINS_PARAM, "*");
        filterHolder.setInitParameter(ALLOWED_METHODS_PARAM, "GET,POST,DELETE,PUT,OPTIONS,PATCH");
        filterHolder.setInitParameter(ALLOWED_HEADERS_PARAM,
                "Authorization, X-Auth-Username, X-Auth-Password, X-Requested-With,Content-Type,Accept,Origin,Access-Control-Request-Headers,cache-control");

        // Initialize GitHub App Installation Access Token cache
        CacheConfigManager cacheConfigManager = CacheConfigManager.getInstance();
        cacheConfigManager.initCache();
    }

    private void registerAPIsAndMisc(Environment environment) {
        ToolsApi toolsApi = new ToolsApi(null);
        environment.jersey().register(toolsApi);

        // TODO: attach V2 final properly
        environment.jersey().register(new io.openapi.api.ToolsApi(null));

        environment.jersey().register(new ToolsExtendedApi());
        environment.jersey().register(new io.openapi.api.ToolClassesApi(null));
        environment.jersey().register(new io.openapi.api.ServiceInfoApi(null));
        environment.jersey().register(new MetadataApi(null));
        environment.jersey().register(new ToolClassesApi(null));
        environment.jersey().register(new PersistenceExceptionMapper());
        environment.jersey().register(new TransactionExceptionMapper());
        environment.jersey().register(new ToolsApiV1());
        environment.jersey().register(new MetadataApiV1());
        environment.jersey().register(new ToolClassesApiV1());

        // extra renderers
        environment.jersey().register(new CharsetResponseFilter());

        // Filter used to log every request an admin user makes.
        environment.jersey().register(new AdminPrivilegesFilter());

        environment.jersey().register(new UsernameRenameRequiredFilter());

        // Swagger providers
        environment.jersey().register(ApiListingResource.class);
        environment.jersey().register(SwaggerSerializers.class);
    }

    private void describeAvailableLanguagePlugins(DefaultPluginManager languagePluginManager) {
        List<PluginWrapper> plugins = languagePluginManager.getStartedPlugins();
        if (plugins.isEmpty()) {
            LOG.info("No language plugins installed");
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("PluginId\tPlugin Version\tPlugin Path\tInitial Path Pattern\tPlugin Type(s)\n");
        for (PluginWrapper plugin : plugins) {
            builder.append(plugin.getPluginId());
            builder.append("\t");
            builder.append(plugin.getPlugin().getWrapper().getDescriptor().getVersion());
            builder.append("\t");
            builder.append(plugin.getPlugin().getWrapper().getPluginPath());
            builder.append("\t");
            List<CompleteLanguageInterface> completeLanguageInterfaces = languagePluginManager.getExtensions(CompleteLanguageInterface.class, plugin.getPluginId());
            List<RecommendedLanguageInterface> recommendedLanguageInterfaces = languagePluginManager.getExtensions(RecommendedLanguageInterface.class, plugin.getPluginId());
            List<MinimalLanguageInterface> minimalLanguageInterfaces = languagePluginManager.getExtensions(MinimalLanguageInterface.class, plugin.getPluginId());
            minimalLanguageInterfaces.forEach(extension -> Joiner.on(',').appendTo(builder, Collections.singleton(extension.initialPathPattern())));
            builder.append("\t");
            minimalLanguageInterfaces.forEach(extension -> Joiner.on(',').appendTo(builder, Collections.singleton("Minimal")));
            builder.append(" ");
            recommendedLanguageInterfaces.forEach(extension -> Joiner.on(',').appendTo(builder, Collections.singleton("Recommended")));
            builder.append(" ");
            completeLanguageInterfaces.forEach(extension -> Joiner.on(',').appendTo(builder, Collections.singleton("Complete")));
            builder.append("\n");
        }
        LOG.info("Started language plugins:\n" + builder);
    }

    public HibernateBundle<DockstoreWebserviceConfiguration> getHibernate() {
        return hibernate;
    }

}
