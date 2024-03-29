/*
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


package org.apache.catalina.startup;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.annotation.HandlesTypes;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.ContainerBase;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.deploy.ErrorPage;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.deploy.ServletDef;
import org.apache.catalina.deploy.WebXml;
import org.apache.catalina.util.ContextName;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.resources.DirContextURLConnection;
import org.apache.naming.resources.ResourceAttributes;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.JarScannerCallback;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.bcel.classfile.AnnotationElementValue;
import org.apache.tomcat.util.bcel.classfile.AnnotationEntry;
import org.apache.tomcat.util.bcel.classfile.ArrayElementValue;
import org.apache.tomcat.util.bcel.classfile.ClassFormatException;
import org.apache.tomcat.util.bcel.classfile.ClassParser;
import org.apache.tomcat.util.bcel.classfile.ElementValue;
import org.apache.tomcat.util.bcel.classfile.ElementValuePair;
import org.apache.tomcat.util.bcel.classfile.JavaClass;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.RuleSet;
import org.apache.tomcat.util.res.StringManager;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * Startup event listener for a <b>Context</b> that configures the properties
 * of that Context, and the associated defined servlets.
 *
 * @author Craig R. McClanahan
 * @author Jean-Francois Arcand
 * @version $Id: ContextConfig.java 1074919 2011-02-26 20:44:34Z markt $
 */

public class ContextConfig
    implements LifecycleListener {

    private static final Log log = LogFactory.getLog( ContextConfig.class );
    
    private static final String SCI_LOCATION =
        "META-INF/services/javax.servlet.ServletContainerInitializer";

    // ----------------------------------------------------- Instance Variables


    /**
     * Custom mappings of login methods to authenticators
     */
    protected Map<String,Authenticator> customAuthenticators;


    /**
     * The set of Authenticators that we know how to configure.  The key is
     * the name of the implemented authentication method, and the value is
     * the fully qualified Java class name of the corresponding Valve.
     */
    protected static Properties authenticators = null;


    /**
     * The Context we are associated with.
     */
    protected Context context = null;


    /**
     * The default web application's context file location.
     */
    protected String defaultContextXml = null;
    
    
    /**
     * The default web application's deployment descriptor location.
     */
    protected String defaultWebXml = null;
    
    
    /**
     * Track any fatal errors during startup configuration processing.
     */
    protected boolean ok = false;


    /**
     * Original docBase.
     */
    protected String originalDocBase = null;
    

    /**
     * Map of ServletContainerInitializer to classes they expressed interest in.
     */
    protected Map<ServletContainerInitializer, Set<Class<?>>> initializerClassMap =
        new LinkedHashMap<ServletContainerInitializer, Set<Class<?>>>();
    
    /**
     * Map of Types to ServletContainerInitializer that are interested in those
     * types.
     */
    protected Map<Class<?>, Set<ServletContainerInitializer>> typeInitializerMap =
        new HashMap<Class<?>, Set<ServletContainerInitializer>>();

    /**
     * The string resources for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * The <code>Digester</code> we will use to process web application
     * context files.
     */
    protected static Digester contextDigester = null;
    
    
    /**
     * The <code>Digester</code> we will use to process web application
     * deployment descriptor files.
     */
    protected Digester webDigester = null;

    /**
     * The <code>Digester</code> we will use to process web fragment
     * deployment descriptor files.
     */
    protected Digester webFragmentDigester = null;

    
    protected static Digester[] webDigesters = new Digester[4];

    /**
     * The <code>Digester</code>s available to process web fragment
     * deployment descriptor files.
     */
    protected static Digester[] webFragmentDigesters = new Digester[4];
    
    /**
     * The <code>Rule</code>s used to parse the web.xml
     */
    protected static WebRuleSet webRuleSet = new WebRuleSet(false);

    /**
     * The <code>Rule</code>s used to parse the web-fragment.xml
     */
    protected static WebRuleSet webFragmentRuleSet = new WebRuleSet(true);

    /**
     * Deployment count.
     */
    protected static long deploymentCount = 0L;
    
    
    protected static final LoginConfig DUMMY_LOGIN_CONFIG =
                                new LoginConfig("NONE", null, null, null);


    // ------------------------------------------------------------- Properties


    /**
     * Return the location of the default deployment descriptor
     */
    public String getDefaultWebXml() {
        if( defaultWebXml == null ) {
            defaultWebXml=Constants.DefaultWebXml;
        }

        return (this.defaultWebXml);

    }


    /**
     * Set the location of the default deployment descriptor
     *
     * @param path Absolute/relative path to the default web.xml
     */
    public void setDefaultWebXml(String path) {

        this.defaultWebXml = path;

    }


    /**
     * Return the location of the default context file
     */
    public String getDefaultContextXml() {
        if( defaultContextXml == null ) {
            defaultContextXml=Constants.DefaultContextXml;
        }

        return (this.defaultContextXml);

    }


    /**
     * Set the location of the default context file
     *
     * @param path Absolute/relative path to the default context.xml
     */
    public void setDefaultContextXml(String path) {

        this.defaultContextXml = path;

    }


    /**
     * Sets custom mappings of login methods to authenticators.
     *
     * @param customAuthenticators Custom mappings of login methods to
     * authenticators
     */
    public void setCustomAuthenticators(
            Map<String,Authenticator> customAuthenticators) {
        this.customAuthenticators = customAuthenticators;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Process events for an associated Context.
     *
     * @param event The lifecycle event that has occurred
     */
    @Override
    public void lifecycleEvent(LifecycleEvent event) {

        // Identify the context we are associated with
        try {
            context = (Context) event.getLifecycle();
        } catch (ClassCastException e) {
            log.error(sm.getString("contextConfig.cce", event.getLifecycle()), e);
            return;
        }

        // Process the event that has occurred
        if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
            configureStart();
        } else if (event.getType().equals(Lifecycle.BEFORE_START_EVENT)) {
            beforeStart();
        } else if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
            // Restore docBase for management tools
            if (originalDocBase != null) {
                String docBase = context.getDocBase();
                context.setDocBase(originalDocBase);
                originalDocBase = docBase;
            }
        } else if (event.getType().equals(Lifecycle.CONFIGURE_STOP_EVENT)) {
            if (originalDocBase != null) {
                String docBase = context.getDocBase();
                context.setDocBase(originalDocBase);
                originalDocBase = docBase;
            }
            configureStop();
        } else if (event.getType().equals(Lifecycle.AFTER_INIT_EVENT)) {
            init();
        } else if (event.getType().equals(Lifecycle.AFTER_DESTROY_EVENT)) {
            destroy();
        }

    }


    // -------------------------------------------------------- protected Methods


    /**
     * Process the application classes annotations, if it exists.
     */
    protected void applicationAnnotationsConfig() {
        
        long t1=System.currentTimeMillis();
        
        WebAnnotationSet.loadApplicationAnnotations(context);
        
        long t2=System.currentTimeMillis();
        if (context instanceof StandardContext) {
            ((StandardContext) context).setStartupTime(t2-t1+
                    ((StandardContext) context).getStartupTime());
        }
    }


    /**
     * Set up an Authenticator automatically if required, and one has not
     * already been configured.
     */
    protected synchronized void authenticatorConfig() {

        // Does this Context require an Authenticator?
        SecurityConstraint constraints[] = context.findConstraints();
        if ((constraints == null) || (constraints.length == 0))
            return;
        LoginConfig loginConfig = context.getLoginConfig();
        if (loginConfig == null) {
            loginConfig = DUMMY_LOGIN_CONFIG;
            context.setLoginConfig(loginConfig);
        }

        // Has an authenticator been configured already?
        if (context.getAuthenticator() != null)
            return;
        
        if (!(context instanceof ContainerBase)) {
            return;     // Cannot install a Valve even if it would be needed
        }

        // Has a Realm been configured for us to authenticate against?
        if (context.getRealm() == null) {
            log.error(sm.getString("contextConfig.missingRealm"));
            ok = false;
            return;
        }

        /*
         * First check to see if there is a custom mapping for the login
         * method. If so, use it. Otherwise, check if there is a mapping in
         * org/apache/catalina/startup/Authenticators.properties.
         */
        Valve authenticator = null;
        if (customAuthenticators != null) {
            authenticator = (Valve)
                customAuthenticators.get(loginConfig.getAuthMethod());
        }
        if (authenticator == null) {
            // Load our mapping properties if necessary
            if (authenticators == null) {
                try {
                    InputStream is=this.getClass().getClassLoader().getResourceAsStream("org/apache/catalina/startup/Authenticators.properties");
                    if( is!=null ) {
                        authenticators = new Properties();
                        authenticators.load(is);
                    } else {
                        log.error(sm.getString(
                                "contextConfig.authenticatorResources"));
                        ok=false;
                        return;
                    }
                } catch (IOException e) {
                    log.error(sm.getString(
                                "contextConfig.authenticatorResources"), e);
                    ok = false;
                    return;
                }
            }

            // Identify the class name of the Valve we should configure
            String authenticatorName = null;
            authenticatorName =
                    authenticators.getProperty(loginConfig.getAuthMethod());
            if (authenticatorName == null) {
                log.error(sm.getString("contextConfig.authenticatorMissing",
                                 loginConfig.getAuthMethod()));
                ok = false;
                return;
            }

            // Instantiate and install an Authenticator of the requested class
            try {
                Class<?> authenticatorClass = Class.forName(authenticatorName);
                authenticator = (Valve) authenticatorClass.newInstance();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString(
                                    "contextConfig.authenticatorInstantiate",
                                    authenticatorName),
                          t);
                ok = false;
            }
        }

        if (authenticator != null && context instanceof ContainerBase) {
            Pipeline pipeline = ((ContainerBase) context).getPipeline();
            if (pipeline != null) {
                ((ContainerBase) context).getPipeline().addValve(authenticator);
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString(
                                    "contextConfig.authenticatorConfigured",
                                    loginConfig.getAuthMethod()));
                }
            }
        }

    }


    /**
     * Create (if necessary) and return a Digester configured to process the
     * web application deployment descriptor (web.xml).
     */
    public void createWebXmlDigester(boolean namespaceAware,
            boolean validation) {
        
        if (!namespaceAware && !validation) {
            if (webDigesters[0] == null) {
                webDigesters[0] = DigesterFactory.newDigester(validation,
                        namespaceAware, webRuleSet);
                webFragmentDigesters[0] = DigesterFactory.newDigester(validation,
                        namespaceAware, webFragmentRuleSet);
            }
            webDigester = webDigesters[0];
            webFragmentDigester = webFragmentDigesters[0];
            
        } else if (!namespaceAware && validation) {
            if (webDigesters[1] == null) {
                webDigesters[1] = DigesterFactory.newDigester(validation,
                        namespaceAware, webRuleSet);
                webFragmentDigesters[1] = DigesterFactory.newDigester(validation,
                        namespaceAware, webFragmentRuleSet);
            }
            webDigester = webDigesters[1];
            webFragmentDigester = webFragmentDigesters[1];
            
        } else if (namespaceAware && !validation) {
            if (webDigesters[2] == null) {
                webDigesters[2] = DigesterFactory.newDigester(validation,
                        namespaceAware, webRuleSet);
                webFragmentDigesters[2] = DigesterFactory.newDigester(validation,
                        namespaceAware, webFragmentRuleSet);
            }
            webDigester = webDigesters[2];
            webFragmentDigester = webFragmentDigesters[2];
            
        } else {
            if (webDigesters[3] == null) {
                webDigesters[3] = DigesterFactory.newDigester(validation,
                        namespaceAware, webRuleSet);
                webFragmentDigesters[3] = DigesterFactory.newDigester(validation,
                        namespaceAware, webFragmentRuleSet);
            }
            webDigester = webDigesters[3];
            webFragmentDigester = webFragmentDigesters[3];
        }
    }

    
    /**
     * Create (if necessary) and return a Digester configured to process the
     * context configuration descriptor for an application.
     */
    protected Digester createContextDigester() {
        Digester digester = new Digester();
        digester.setValidating(false);
        digester.setRulesValidation(true);
        HashMap<Class<?>, List<String>> fakeAttributes =
            new HashMap<Class<?>, List<String>>();
        ArrayList<String> attrs = new ArrayList<String>();
        attrs.add("className");
        fakeAttributes.put(Object.class, attrs);
        digester.setFakeAttributes(fakeAttributes);
        RuleSet contextRuleSet = new ContextRuleSet("", false);
        digester.addRuleSet(contextRuleSet);
        RuleSet namingRuleSet = new NamingRuleSet("Context/");
        digester.addRuleSet(namingRuleSet);
        return digester;
    }


    protected String getBaseDir() {
        Container engineC=context.getParent().getParent();
        if( engineC instanceof StandardEngine ) {
            return ((StandardEngine)engineC).getBaseDir();
        }
        return System.getProperty(Globals.CATALINA_BASE_PROP);
    }

    
    /**
     * Process the default configuration file, if it exists.
     */
    protected void contextConfig() {
        
        // Open the default context.xml file, if it exists
        if( defaultContextXml==null && context instanceof StandardContext ) {
            defaultContextXml = ((StandardContext)context).getDefaultContextXml();
        }
        // set the default if we don't have any overrides
        if( defaultContextXml==null ) getDefaultContextXml();

        if (!context.getOverride()) {
            File defaultContextFile = new File(defaultContextXml);
            if (!defaultContextFile.isAbsolute()) {
                defaultContextFile =new File(getBaseDir(), defaultContextXml);
            }
            if (defaultContextFile.exists()) {
                try {
                    URL defaultContextUrl = defaultContextFile.toURI().toURL();
                    processContextConfig(defaultContextUrl);
                } catch (MalformedURLException e) {
                    log.error(sm.getString(
                            "contextConfig.badUrl", defaultContextFile), e);
                }
            }
            
            File hostContextFile = new File(getConfigBase(),
                    getHostConfigPath(Constants.HostContextXml));
            if (hostContextFile.exists()) {
                try {
                    URL hostContextUrl = hostContextFile.toURI().toURL();
                    processContextConfig(hostContextUrl);
                } catch (MalformedURLException e) {
                    log.error(sm.getString(
                            "contextConfig.badUrl", hostContextFile), e);
                }
            }
        }
        if (context.getConfigFile() != null)
            processContextConfig(context.getConfigFile());
        
    }

    
    /**
     * Process a context.xml.
     */
    protected void processContextConfig(URL contextXml) {
        
        if (log.isDebugEnabled())
            log.debug("Processing context [" + context.getName() 
                    + "] configuration file [" + contextXml + "]");

        InputSource source = null;
        InputStream stream = null;

        try {
            source = new InputSource(contextXml.toString());
            stream = contextXml.openStream();
            
            // Add as watched resource so that cascade reload occurs if a default
            // config file is modified/added/removed
            if (contextXml.getProtocol() == "file") {
                context.addWatchedResource(
                        (new File(contextXml.toURI())).getAbsolutePath());
            }
        } catch (Exception e) {
            log.error(sm.getString("contextConfig.contextMissing",  
                      contextXml) , e);
        }
        
        if (source == null)
            return;
        synchronized (contextDigester) {
            try {
                source.setByteStream(stream);
                contextDigester.setClassLoader(this.getClass().getClassLoader());
                contextDigester.setUseContextClassLoader(false);
                contextDigester.push(context.getParent());
                contextDigester.push(context);
                XmlErrorHandler errorHandler = new XmlErrorHandler();
                contextDigester.setErrorHandler(errorHandler);
                contextDigester.parse(source);
                if (errorHandler.getWarnings().size() > 0 ||
                        errorHandler.getErrors().size() > 0) {
                    errorHandler.logFindings(log, contextXml.toString());
                    ok = false;
                }
                if (log.isDebugEnabled())
                    log.debug("Successfully processed context [" + context.getName() 
                            + "] configuration file [" + contextXml + "]");
            } catch (SAXParseException e) {
                log.error(sm.getString("contextConfig.contextParse",
                        context.getName()), e);
                log.error(sm.getString("contextConfig.defaultPosition",
                                 "" + e.getLineNumber(),
                                 "" + e.getColumnNumber()));
                ok = false;
            } catch (Exception e) {
                log.error(sm.getString("contextConfig.contextParse",
                        context.getName()), e);
                ok = false;
            } finally {
                contextDigester.reset();
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (IOException e) {
                    log.error(sm.getString("contextConfig.contextClose"), e);
                }
            }
        }
    }

    
    /**
     * Adjust docBase.
     */
    protected void fixDocBase()
        throws IOException {
        
        Host host = (Host) context.getParent();
        String appBase = host.getAppBase();

        boolean unpackWARs = true;
        if (host instanceof StandardHost) {
            unpackWARs = ((StandardHost) host).isUnpackWARs() 
                && ((StandardContext) context).getUnpackWAR();
        }

        File canonicalAppBase = new File(appBase);
        if (canonicalAppBase.isAbsolute()) {
            canonicalAppBase = canonicalAppBase.getCanonicalFile();
        } else {
            canonicalAppBase = 
                new File(System.getProperty(Globals.CATALINA_BASE_PROP), appBase)
                .getCanonicalFile();
        }

        String docBase = context.getDocBase();
        if (docBase == null) {
            // Trying to guess the docBase according to the path
            String path = context.getPath();
            if (path == null) {
                return;
            }
            ContextName cn = new ContextName(path, context.getWebappVersion());
            docBase = cn.getBaseName();
        }

        File file = new File(docBase);
        if (!file.isAbsolute()) {
            docBase = (new File(canonicalAppBase, docBase)).getPath();
        } else {
            docBase = file.getCanonicalPath();
        }
        file = new File(docBase);
        String origDocBase = docBase;
        
        ContextName cn = new ContextName(context.getPath(),
                context.getWebappVersion());
        String pathName = cn.getBaseName();

        if (docBase.toLowerCase(Locale.ENGLISH).endsWith(".war") && !file.isDirectory() && unpackWARs) {
            URL war = new URL("jar:" + (new File(docBase)).toURI().toURL() + "!/");
            docBase = ExpandWar.expand(host, war, pathName);
            file = new File(docBase);
            docBase = file.getCanonicalPath();
            if (context instanceof StandardContext) {
                ((StandardContext) context).setOriginalDocBase(origDocBase);
            }
        } else if (docBase.toLowerCase(Locale.ENGLISH).endsWith(".war") &&
                !file.isDirectory() && !unpackWARs) {
            URL war =
                new URL("jar:" + (new File (docBase)).toURI().toURL() + "!/");
            ExpandWar.validate(host, war, pathName);
        } else {
            File docDir = new File(docBase);
            if (!docDir.exists()) {
                File warFile = new File(docBase + ".war");
                if (warFile.exists()) {
                    URL war =
                        new URL("jar:" + warFile.toURI().toURL() + "!/");
                    if (unpackWARs) {
                        docBase = ExpandWar.expand(host, war, pathName);
                        file = new File(docBase);
                        docBase = file.getCanonicalPath();
                    } else {
                        docBase = warFile.getCanonicalPath();
                        ExpandWar.validate(host, war, pathName);
                    }
                }
                if (context instanceof StandardContext) {
                    ((StandardContext) context).setOriginalDocBase(origDocBase);
                }
            }
        }

        if (docBase.startsWith(canonicalAppBase.getPath() + File.separatorChar)) {
            docBase = docBase.substring(canonicalAppBase.getPath().length());
            docBase = docBase.replace(File.separatorChar, '/');
            if (docBase.startsWith("/")) {
                docBase = docBase.substring(1);
            }
        } else {
            docBase = docBase.replace(File.separatorChar, '/');
        }

        context.setDocBase(docBase);

    }
    
    
    protected void antiLocking() {

        if ((context instanceof StandardContext) 
            && ((StandardContext) context).getAntiResourceLocking()) {
            
            Host host = (Host) context.getParent();
            String appBase = host.getAppBase();
            String docBase = context.getDocBase();
            if (docBase == null)
                return;
            if (originalDocBase == null) {
                originalDocBase = docBase;
            } else {
                docBase = originalDocBase;
            }
            File docBaseFile = new File(docBase);
            if (!docBaseFile.isAbsolute()) {
                File file = new File(appBase);
                if (!file.isAbsolute()) {
                    file = new File(System.getProperty(Globals.CATALINA_BASE_PROP), appBase);
                }
                docBaseFile = new File(file, docBase);
            }
            
            String path = context.getPath();
            if (path == null) {
                return;
            }
            ContextName cn = new ContextName(path, context.getWebappVersion());
            docBase = cn.getBaseName();

            File file = null;
            if (docBase.toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                // TODO - This is never executed. Bug or code to delete?
                file = new File(System.getProperty("java.io.tmpdir"),
                        deploymentCount++ + "-" + docBase + ".war");
            } else {
                file = new File(System.getProperty("java.io.tmpdir"), 
                        deploymentCount++ + "-" + docBase);
            }
            
            if (log.isDebugEnabled())
                log.debug("Anti locking context[" + context.getName() 
                        + "] setting docBase to " + file);
            
            // Cleanup just in case an old deployment is lying around
            ExpandWar.delete(file);
            if (ExpandWar.copy(docBaseFile, file)) {
                context.setDocBase(file.getAbsolutePath());
            }
            
        }
        
    }
    

    /**
     * Process a "init" event for this Context.
     */
    protected void init() {
        // Called from StandardContext.init()

        if (contextDigester == null){
            contextDigester = createContextDigester();
            contextDigester.getParser();
        }

        if (log.isDebugEnabled())
            log.debug(sm.getString("contextConfig.init"));
        context.setConfigured(false);
        ok = true;
        
        contextConfig();
        
        try {
            fixDocBase();
        } catch (IOException e) {
            log.error(sm.getString(
                    "contextConfig.fixDocBase", context.getName()), e);
        }
        
    }
    
    
    /**
     * Process a "before start" event for this Context.
     */
    protected synchronized void beforeStart() {
        
        antiLocking();

    }
    
    
    /**
     * Process a "contextConfig" event for this Context.
     */
    protected synchronized void configureStart() {
        // Called from StandardContext.start()

        if (log.isDebugEnabled())
            log.debug(sm.getString("contextConfig.start"));

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("contextConfig.xmlSettings",
                    context.getName(),
                    Boolean.valueOf(context.getXmlValidation()),
                    Boolean.valueOf(context.getXmlNamespaceAware())));
        }
        
        createWebXmlDigester(context.getXmlNamespaceAware(), context.getXmlValidation());
        
        webConfig();

        if (!context.getIgnoreAnnotations()) {
            applicationAnnotationsConfig();
        }
        if (ok) {
            validateSecurityRoles();
        }

        // Configure an authenticator if we need one
        if (ok)
            authenticatorConfig();

        // Dump the contents of this pipeline if requested
        if ((log.isDebugEnabled()) && (context instanceof ContainerBase)) {
            log.debug("Pipeline Configuration:");
            Pipeline pipeline = ((ContainerBase) context).getPipeline();
            Valve valves[] = null;
            if (pipeline != null)
                valves = pipeline.getValves();
            if (valves != null) {
                for (int i = 0; i < valves.length; i++) {
                    log.debug("  " + valves[i].getInfo());
                }
            }
            log.debug("======================");
        }

        // Make our application available if no problems were encountered
        if (ok)
            context.setConfigured(true);
        else {
            log.error(sm.getString("contextConfig.unavailable"));
            context.setConfigured(false);
        }

    }


    /**
     * Process a "stop" event for this Context.
     */
    protected synchronized void configureStop() {

        if (log.isDebugEnabled())
            log.debug(sm.getString("contextConfig.stop"));

        int i;

        // Removing children
        Container[] children = context.findChildren();
        for (i = 0; i < children.length; i++) {
            context.removeChild(children[i]);
        }

        // Removing application parameters
        /*
        ApplicationParameter[] applicationParameters =
            context.findApplicationParameters();
        for (i = 0; i < applicationParameters.length; i++) {
            context.removeApplicationParameter
                (applicationParameters[i].getName());
        }
        */

        // Removing security constraints
        SecurityConstraint[] securityConstraints = context.findConstraints();
        for (i = 0; i < securityConstraints.length; i++) {
            context.removeConstraint(securityConstraints[i]);
        }

        // Removing Ejbs
        /*
        ContextEjb[] contextEjbs = context.findEjbs();
        for (i = 0; i < contextEjbs.length; i++) {
            context.removeEjb(contextEjbs[i].getName());
        }
        */

        // Removing environments
        /*
        ContextEnvironment[] contextEnvironments = context.findEnvironments();
        for (i = 0; i < contextEnvironments.length; i++) {
            context.removeEnvironment(contextEnvironments[i].getName());
        }
        */

        // Removing errors pages
        ErrorPage[] errorPages = context.findErrorPages();
        for (i = 0; i < errorPages.length; i++) {
            context.removeErrorPage(errorPages[i]);
        }

        // Removing filter defs
        FilterDef[] filterDefs = context.findFilterDefs();
        for (i = 0; i < filterDefs.length; i++) {
            context.removeFilterDef(filterDefs[i]);
        }

        // Removing filter maps
        FilterMap[] filterMaps = context.findFilterMaps();
        for (i = 0; i < filterMaps.length; i++) {
            context.removeFilterMap(filterMaps[i]);
        }

        // Removing local ejbs
        /*
        ContextLocalEjb[] contextLocalEjbs = context.findLocalEjbs();
        for (i = 0; i < contextLocalEjbs.length; i++) {
            context.removeLocalEjb(contextLocalEjbs[i].getName());
        }
        */

        // Removing Mime mappings
        String[] mimeMappings = context.findMimeMappings();
        for (i = 0; i < mimeMappings.length; i++) {
            context.removeMimeMapping(mimeMappings[i]);
        }

        // Removing parameters
        String[] parameters = context.findParameters();
        for (i = 0; i < parameters.length; i++) {
            context.removeParameter(parameters[i]);
        }

        // Removing resource env refs
        /*
        String[] resourceEnvRefs = context.findResourceEnvRefs();
        for (i = 0; i < resourceEnvRefs.length; i++) {
            context.removeResourceEnvRef(resourceEnvRefs[i]);
        }
        */

        // Removing resource links
        /*
        ContextResourceLink[] contextResourceLinks =
            context.findResourceLinks();
        for (i = 0; i < contextResourceLinks.length; i++) {
            context.removeResourceLink(contextResourceLinks[i].getName());
        }
        */

        // Removing resources
        /*
        ContextResource[] contextResources = context.findResources();
        for (i = 0; i < contextResources.length; i++) {
            context.removeResource(contextResources[i].getName());
        }
        */

        // Removing security role
        String[] securityRoles = context.findSecurityRoles();
        for (i = 0; i < securityRoles.length; i++) {
            context.removeSecurityRole(securityRoles[i]);
        }

        // Removing servlet mappings
        String[] servletMappings = context.findServletMappings();
        for (i = 0; i < servletMappings.length; i++) {
            context.removeServletMapping(servletMappings[i]);
        }

        // FIXME : Removing status pages

        // Removing welcome files
        String[] welcomeFiles = context.findWelcomeFiles();
        for (i = 0; i < welcomeFiles.length; i++) {
            context.removeWelcomeFile(welcomeFiles[i]);
        }

        // Removing wrapper lifecycles
        String[] wrapperLifecycles = context.findWrapperLifecycles();
        for (i = 0; i < wrapperLifecycles.length; i++) {
            context.removeWrapperLifecycle(wrapperLifecycles[i]);
        }

        // Removing wrapper listeners
        String[] wrapperListeners = context.findWrapperListeners();
        for (i = 0; i < wrapperListeners.length; i++) {
            context.removeWrapperListener(wrapperListeners[i]);
        }

        // Remove (partially) folders and files created by antiLocking
        Host host = (Host) context.getParent();
        String appBase = host.getAppBase();
        String docBase = context.getDocBase();
        if ((docBase != null) && (originalDocBase != null)) {
            File docBaseFile = new File(docBase);
            if (!docBaseFile.isAbsolute()) {
                docBaseFile = new File(appBase, docBase);
            }
            // No need to log failure - it is expected in this case
            ExpandWar.delete(docBaseFile, false);
        }
        
        // Reset ServletContextInitializer scanning
        initializerClassMap.clear();
        typeInitializerMap.clear();
        
        ok = true;

    }
    
    
    /**
     * Process a "destroy" event for this Context.
     */
    protected synchronized void destroy() {
        // Called from StandardContext.destroy()
        if (log.isDebugEnabled())
            log.debug(sm.getString("contextConfig.destroy"));

        // Changed to getWorkPath per Bugzilla 35819.
        String workDir = ((StandardContext) context).getWorkPath();
        if (workDir != null)
            ExpandWar.delete(new File(workDir));
    }
    
    
    /**
     * Validate the usage of security role names in the web application
     * deployment descriptor.  If any problems are found, issue warning
     * messages (for backwards compatibility) and add the missing roles.
     * (To make these problems fatal instead, simply set the <code>ok</code>
     * instance variable to <code>false</code> as well).
     */
    protected void validateSecurityRoles() {

        // Check role names used in <security-constraint> elements
        SecurityConstraint constraints[] = context.findConstraints();
        for (int i = 0; i < constraints.length; i++) {
            String roles[] = constraints[i].findAuthRoles();
            for (int j = 0; j < roles.length; j++) {
                if (!"*".equals(roles[j]) &&
                    !context.findSecurityRole(roles[j])) {
                    log.info(sm.getString("contextConfig.role.auth", roles[j]));
                    context.addSecurityRole(roles[j]);
                }
            }
        }

        // Check role names used in <servlet> elements
        Container wrappers[] = context.findChildren();
        for (int i = 0; i < wrappers.length; i++) {
            Wrapper wrapper = (Wrapper) wrappers[i];
            String runAs = wrapper.getRunAs();
            if ((runAs != null) && !context.findSecurityRole(runAs)) {
                log.info(sm.getString("contextConfig.role.runas", runAs));
                context.addSecurityRole(runAs);
            }
            String names[] = wrapper.findSecurityReferences();
            for (int j = 0; j < names.length; j++) {
                String link = wrapper.findSecurityReference(names[j]);
                if ((link != null) && !context.findSecurityRole(link)) {
                    log.info(sm.getString("contextConfig.role.link", link));
                    context.addSecurityRole(link);
                }
            }
        }

    }


    /**
     * Get config base.
     */
    protected File getConfigBase() {
        File configBase = 
            new File(System.getProperty(Globals.CATALINA_BASE_PROP), "conf");
        if (!configBase.exists()) {
            return null;
        }
        return configBase;
    }  

    
    protected String getHostConfigPath(String resourceName) {
        StringBuilder result = new StringBuilder();
        Container container = context;
        Container host = null;
        Container engine = null;
        while (container != null) {
            if (container instanceof Host)
                host = container;
            if (container instanceof Engine)
                engine = container;
            container = container.getParent();
        }
        if (engine != null) {
            result.append(engine.getName()).append('/');
        }
        if (host != null) {
            result.append(host.getName()).append('/');
        }
        result.append(resourceName);
        return result.toString();
    }


    /**
     * Scan the web.xml files that apply to the web application and merge them
     * using the rules defined in the spec. For the global web.xml files,
     * where there is duplicate configuration, the most specific level wins. ie
     * an application's web.xml takes precedence over the host level or global
     * web.xml file.
     */
    protected void webConfig() {
        WebXml webXml = createWebXml();

        // Parse global web.xml if present
        InputSource globalWebXml = getGlobalWebXmlSource();
        if (globalWebXml == null) {
            // This is unusual enough to log
            log.info(sm.getString("contextConfig.defaultMissing"));
        } else {
            parseWebXml(globalWebXml, webXml, false);
        }

        // Parse host level web.xml if present
        // Additive apart from welcome pages
        webXml.setReplaceWelcomeFiles(true);
        InputSource hostWebXml = getHostWebXmlSource();
        parseWebXml(hostWebXml, webXml, false);
        
        // Parse context level web.xml
        webXml.setReplaceWelcomeFiles(true);
        InputSource contextWebXml = getContextWebXmlSource();
        parseWebXml(contextWebXml, webXml, false);
        
        // Assuming 0 is safe for what is required in this case
        double webXmlVersion = 0;
        if (webXml.getVersion() != null) {
            webXmlVersion = Double.parseDouble(webXml.getVersion());
        }
        
        if (webXmlVersion >= 3) {
            // Ordering is important here

            // Step 1. Identify all the JARs packaged with the application
            // If the JARs have a web-fragment.xml it will be parsed at this
            // point.
            Map<String,WebXml> fragments = processJarsForWebFragments();

            // Only need to process fragments and annotations if metadata is
            // not complete
            Set<WebXml> orderedFragments = null;
            if  (!webXml.isMetadataComplete()) {
                // Step 2. Order the fragments.
                orderedFragments = WebXml.orderWebFragments(webXml, fragments);
    
                // Step 3. Look for ServletContainerInitializer implementations
                if (ok) {
                    processServletContainerInitializers(orderedFragments);
                }
    
                // Step 4. Process /WEB-INF/classes for annotations
                // This will add any matching classes to the typeInitializerMap
                if (ok) {
                    URL webinfClasses;
                    try {
                        webinfClasses = context.getServletContext().getResource(
                                "/WEB-INF/classes");
                        processAnnotationsUrl(webinfClasses, webXml);
                    } catch (MalformedURLException e) {
                        log.error(sm.getString(
                                "contextConfig.webinfClassesUrl"), e);
                    }
                }
    
                // Step 5. Process JARs for annotations - only need to process
                // those fragments we are going to use
                // This will add any matching classes to the typeInitializerMap
                if (ok) {
                    processAnnotations(orderedFragments);
                }
    
                // Step 6. Merge web-fragment.xml files into the main web.xml
                // file.
                if (ok) {
                    ok = webXml.merge(orderedFragments);
                }
    
                // Step 6.5 Convert explicitly mentioned jsps to servlets
                if (!false) {
                    convertJsps(webXml);
                }
    
                // Step 7. Apply merged web.xml to Context
                if (ok) {
                    webXml.configureContext(context);
    
                    // Step 7a. Make the merged web.xml available to other
                    // components, specifically Jasper, to save those components
                    // from having to re-generate it.
                    // TODO Use a ServletContainerInitializer for Jasper
                    String mergedWebXml = webXml.toXml();
                    context.getServletContext().setAttribute(
                           org.apache.tomcat.util.scan.Constants.MERGED_WEB_XML,
                            mergedWebXml);
                    if (context.getLogEffectiveWebXml()) {
                        log.info("web.xml:\n" + mergedWebXml);
                    }
                }
            } else {
                webXml.configureContext(context);
            }
            
            // Always need to look for static resources
            // Step 8. Look for static resources packaged in JARs
            if (ok) {
                // Spec does not define an order.
                // Use ordered JARs followed by remaining JARs
                Set<WebXml> resourceJars = new LinkedHashSet<WebXml>();
                if (orderedFragments != null) {
                    for (WebXml fragment : orderedFragments) {
                        resourceJars.add(fragment);
                    }
                }
                for (WebXml fragment : fragments.values()) {
                    if (!resourceJars.contains(fragment)) {
                        resourceJars.add(fragment);
                    }
                }
                processResourceJARs(resourceJars);
                // See also StandardContext.resourcesStart() for
                // WEB-INF/classes/META-INF/resources configuration
            }
            
            // Only look for ServletContainerInitializer if metadata is not
            // complete
            if (!webXml.isMetadataComplete()) {
                // Step 9. Apply the ServletContainerInitializer config to the
                // context
                if (ok) {
                    for (Map.Entry<ServletContainerInitializer,
                            Set<Class<?>>> entry : 
                                initializerClassMap.entrySet()) {
                        if (entry.getValue().isEmpty()) {
                            context.addServletContainerInitializer(
                                    entry.getKey(), null);
                        } else {
                            context.addServletContainerInitializer(
                                    entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        } else {
            // Apply unmerged web.xml to Context
            convertJsps(webXml);
            webXml.configureContext(context);
        }
    }

    private void convertJsps(WebXml webXml) {
        ServletDef jspServlet = webXml.getServlets().get("jsp");
        for (ServletDef servletDef: webXml.getServlets().values()) {
            if (servletDef.getJspFile() != null) {
                convertJsp(servletDef, jspServlet);
            }
        }
    }

    private void convertJsp(ServletDef servletDef, ServletDef jspServletDef) {
        servletDef.setServletClass(org.apache.catalina.core.Constants.JSP_SERVLET_CLASS);
        String jspFile = servletDef.getJspFile();
        if ((jspFile != null) && !jspFile.startsWith("/")) {
            if (context.isServlet22()) {
                if(log.isDebugEnabled())
                    log.debug(sm.getString("contextConfig.jspFile.warning",
                                       jspFile));
                jspFile = "/" + jspFile;
            } else {
                throw new IllegalArgumentException
                    (sm.getString("contextConfig.jspFile.error", jspFile));
            }
        }
        servletDef.getParameterMap().put("jspFile", jspFile);
        servletDef.setJspFile(null);
        for (Map.Entry<String, String> initParam: jspServletDef.getParameterMap().entrySet()) {
            servletDef.addInitParameter(initParam.getKey(), initParam.getValue());
        }
    }

    protected WebXml createWebXml() {
        return new WebXml();
    }

    /**
     * Scan JARs for ServletContainerInitializer implementations.
     * Implementations will be added in web-fragment.xml priority order.
     */
    protected void processServletContainerInitializers(
            Set<WebXml> fragments) {
        
        for (WebXml fragment : fragments) {
            URL url = fragment.getURL();
            JarFile jarFile = null;
            InputStream is = null;
            ServletContainerInitializer sci = null;
            try {
                if ("jar".equals(url.getProtocol())) {
                    JarURLConnection conn =
                        (JarURLConnection) url.openConnection();
                    jarFile = conn.getJarFile();
                    ZipEntry entry = jarFile.getEntry(SCI_LOCATION);
                    if (entry != null) {
                        is = jarFile.getInputStream(entry);
                    }
                } else if ("file".equals(url.getProtocol())) {
                    String path = url.getPath();
                    File file = new File(path, SCI_LOCATION);
                    if (file.exists()) {
                        is = new FileInputStream(file);
                    }
                }
                if (is != null) {
                    sci = getServletContainerInitializer(is);
                }
            } catch (IOException ioe) {
                log.error(sm.getString(
                        "contextConfig.servletContainerInitializerFail", url,
                        context.getName()));
                ok = false;
                return;
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                if (jarFile != null) {
                    try {
                        jarFile.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
            
            if (sci == null) {
                continue;
            }

            initializerClassMap.put(sci, new HashSet<Class<?>>());
            
            HandlesTypes ht =
                sci.getClass().getAnnotation(HandlesTypes.class);
            if (ht != null) {
                Class<?>[] types = ht.value();
                if (types != null) {
                    for (Class<?> type : types) {
                        Set<ServletContainerInitializer> scis =
                            typeInitializerMap.get(type);
                        if (scis == null) {
                            scis = new HashSet<ServletContainerInitializer>();
                            typeInitializerMap.put(type, scis);
                        }
                        scis.add(sci);
                    }
                }
            }

        }
    }
    
    
    /**
     * Extract the name of the ServletContainerInitializer.
     * 
     * @param is    The resource where the name is defined 
     * @return      The class name
     * @throws IOException
     */
    protected ServletContainerInitializer getServletContainerInitializer(
            InputStream is) throws IOException {

        String className = null;
        
        if (is != null) {
            String line = null;
            try {
                BufferedReader br =
                    new BufferedReader(new InputStreamReader(is, "UTF-8"));
                line = br.readLine();
                if (line != null && line.trim().length() > 0) {
                    className = line.trim();
                }
            } catch (UnsupportedEncodingException e) {
                // Should never happen with UTF-8
                // If it does - ignore & return null
            }
        }
        
        ServletContainerInitializer sci = null;
        try {
            Class<?> clazz = Class.forName(className,true,
                    context.getLoader().getClassLoader());
             sci = (ServletContainerInitializer) clazz.newInstance();
        } catch (ClassNotFoundException e) {
            log.error(sm.getString("contextConfig.invalidSci", className), e);
            throw new IOException(e);
        } catch (InstantiationException e) {
            log.error(sm.getString("contextConfig.invalidSci", className), e);
            throw new IOException(e);
        } catch (IllegalAccessException e) {
            log.error(sm.getString("contextConfig.invalidSci", className), e);
            throw new IOException(e);
        }
        
        return sci;
    }

    
    /**
     * Scan JARs that contain web-fragment.xml files that will be used to
     * configure this application to see if they also contain static resources.
     * If static resources are found, add them to the context. Resources are
     * added in web-fragment.xml priority order.
     */
    protected void processResourceJARs(Set<WebXml> fragments) {
        for (WebXml fragment : fragments) {
            URL url = fragment.getURL();
            JarFile jarFile = null;
            try {
                // Note: Ignore file URLs for now since only jar URLs will be accepted
                if ("jar".equals(url.getProtocol())) {
                    JarURLConnection conn =
                        (JarURLConnection) url.openConnection();
                    jarFile = conn.getJarFile();   
                    ZipEntry entry = jarFile.getEntry("META-INF/resources/");
                    if (entry != null) {
                        context.addResourceJarUrl(url);
                    }
                }
            } catch (IOException ioe) {
                log.error(sm.getString("contextConfig.resourceJarFail", url,
                        context.getName()));
            } finally {
                if (jarFile != null) {
                    try {
                        jarFile.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }
    
    
    /**
     * Identify the default web.xml to be used and obtain an input source for
     * it.
     */
    protected InputSource getGlobalWebXmlSource() {
        // Is a default web.xml specified for the Context?
        if (defaultWebXml == null && context instanceof StandardContext) {
            defaultWebXml = ((StandardContext) context).getDefaultWebXml();
        }
        // Set the default if we don't have any overrides
        if (defaultWebXml == null) getDefaultWebXml();

        return getWebXmlSource(defaultWebXml, getBaseDir());
    }
    
    /**
     * Identify the host web.xml to be used and obtain an input source for
     * it.
     */
    protected InputSource getHostWebXmlSource() {
        String resourceName = getHostConfigPath(Constants.HostWebXml);
        
        // In an embedded environment, configBase might not be set
        File configBase = getConfigBase();
        if (configBase == null)
            return null;
        
        String basePath = null;
        try {
            basePath = configBase.getCanonicalPath();
        } catch (IOException e) {
            log.error(sm.getString("contectConfig.baseError"), e);
            return null;
        }

        return getWebXmlSource(resourceName, basePath);
    }
    
    /**
     * Identify the application web.xml to be used and obtain an input source
     * for it.
     */
    protected InputSource getContextWebXmlSource() {
        InputStream stream = null;
        InputSource source = null;
        URL url = null;
        
        String altDDName = null;

        // Open the application web.xml file, if it exists
        ServletContext servletContext = context.getServletContext();
        if (servletContext != null) {
            altDDName = (String)servletContext.getAttribute(
                                                        Globals.ALT_DD_ATTR);
            if (altDDName != null) {
                try {
                    stream = new FileInputStream(altDDName);
                    url = new File(altDDName).toURI().toURL();
                } catch (FileNotFoundException e) {
                    log.error(sm.getString("contextConfig.altDDNotFound",
                                           altDDName));
                } catch (MalformedURLException e) {
                    log.error(sm.getString("contextConfig.applicationUrl"));
                }
            }
            else {
                stream = servletContext.getResourceAsStream
                    (Constants.ApplicationWebXml);
                try {
                    url = servletContext.getResource(
                            Constants.ApplicationWebXml);
                } catch (MalformedURLException e) {
                    log.error(sm.getString("contextConfig.applicationUrl"));
                }
            }
        }
        if (stream == null || url == null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("contextConfig.applicationMissing") + " " + context);
            }
        } else {
            source = new InputSource(url.toExternalForm());
            source.setByteStream(stream);
        }
        
        return source;
    }
    
    /**
     * 
     * @param filename  Name of the file (possibly with one or more leading path
     *                  segments) to read
     * @param path      Location that filename is relative to 
     */
    protected InputSource getWebXmlSource(String filename, String path) {
        File file = new File(filename);
        if (!file.isAbsolute()) {
            file = new File(path, filename);
        }

        InputStream stream = null;
        InputSource source = null;

        try {
            if (!file.exists()) {
                // Use getResource and getResourceAsStream
                stream =
                    getClass().getClassLoader().getResourceAsStream(filename);
                if(stream != null) {
                    source =
                        new InputSource(getClass().getClassLoader().getResource(
                                filename).toString());
                } 
            } else {
                source = new InputSource("file://" + file.getAbsolutePath());
                stream = new FileInputStream(file);
                context.addWatchedResource(file.getAbsolutePath());
            }

            if (stream != null && source != null) {
                source.setByteStream(stream);
            }
        } catch (Exception e) {
            log.error(sm.getString(
                    "contextConfig.defaultError", filename, file), e);
        }

        return source;
    }


    protected void parseWebXml(InputSource source, WebXml dest,
            boolean fragment) {
        
        if (source == null) return;

        XmlErrorHandler handler = new XmlErrorHandler();

        // Web digesters and rulesets are shared between contexts but are not
        // thread safe. Whilst there should only be one thread at a time
        // processing a config, play safe and sync.
        Digester digester;
        if (fragment) {
            digester = webFragmentDigester;
        } else {
            digester = webDigester;
        }
        
        synchronized(digester) {
            
            digester.push(dest);
            digester.setErrorHandler(handler);
            
            if(log.isDebugEnabled()) {
                log.debug(sm.getString("contextConfig.applicationStart",
                        source.getSystemId()));
            }

            try {
                digester.parse(source);

                if (handler.getWarnings().size() > 0 ||
                        handler.getErrors().size() > 0) {
                    ok = false;
                    handler.logFindings(log, source.getSystemId());
                }
            } catch (SAXParseException e) {
                log.error(sm.getString("contextConfig.applicationParse",
                        source.getSystemId()), e);
                log.error(sm.getString("contextConfig.applicationPosition",
                                 "" + e.getLineNumber(),
                                 "" + e.getColumnNumber()));
                ok = false;
            } catch (Exception e) {
                log.error(sm.getString("contextConfig.applicationParse",
                        source.getSystemId()), e);
                ok = false;
            } finally {
                digester.reset();
                if (fragment) {
                    webFragmentRuleSet.recycle();
                } else {
                    webRuleSet.recycle();
                }
            }
        }
    }


    /**
     * Scan /META-INF/lib for JARs and for each one found add it and any
     * /META-INF/web-fragment.xml to the resulting Map. web-fragment.xml files
     * will be parsed before being added to the map. Every JAR will be added and
     * <code>null</code> will be used if no web-fragment.xml was found. Any JARs
     * known not contain fragments will be skipped.
     * 
     * @return A map of JAR name to processed web fragment (if any)
     */
    protected Map<String,WebXml> processJarsForWebFragments() {
        
        JarScanner jarScanner = context.getJarScanner();
        FragmentJarScannerCallback callback = new FragmentJarScannerCallback();
        
        jarScanner.scan(context.getServletContext(),
                context.getLoader().getClassLoader(), callback, null);
        
        return callback.getFragments();
    }

    protected void processAnnotations(Set<WebXml> fragments) {
        for(WebXml fragment : fragments) {
            if (!fragment.isMetadataComplete()) {
                WebXml annotations = new WebXml();
                // no impact on distributable
                annotations.setDistributable(true);
                URL url = fragment.getURL();
                processAnnotationsUrl(url, annotations);
                Set<WebXml> set = new HashSet<WebXml>();
                set.add(annotations);
                // Merge annotations into fragment - fragment takes priority
                fragment.merge(set);
            }
        }
    }

    protected void processAnnotationsUrl(URL url, WebXml fragment) {
        if (url == null) {
            // Nothing to do.
            return;
        } else if ("jar".equals(url.getProtocol())) {
            processAnnotationsJar(url, fragment);
        } else if ("jndi".equals(url.getProtocol())) {
            processAnnotationsJndi(url, fragment);
        } else if ("file".equals(url.getProtocol())) {
            try {
                processAnnotationsFile(new File(url.toURI()), fragment);
            } catch (URISyntaxException e) {
                log.error(sm.getString("contextConfig.fileUrl", url), e);
            }
        } else {
            log.error(sm.getString("contextConfig.unknownUrlProtocol",
                    url.getProtocol(), url));
        }
        
    }


    protected void processAnnotationsJar(URL url, WebXml fragment) {
        JarFile jarFile = null;
        
        try {
            URLConnection urlConn = url.openConnection();
            JarURLConnection jarUrlConn;
            if (!(urlConn instanceof JarURLConnection)) {
                // This should never happen
                sm.getString("contextConfig.jarUrl", url);
                return;
            }
            
            jarUrlConn = (JarURLConnection) urlConn;
            jarUrlConn.setUseCaches(false);
            jarFile = jarUrlConn.getJarFile();
            
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String entryName = jarEntry.getName();
                if (entryName.endsWith(".class")) {
                    InputStream is = null;
                    try {
                        is = jarFile.getInputStream(jarEntry);
                        processAnnotationsStream(is, fragment);
                    } catch (IOException e) {
                        log.error(sm.getString("contextConfig.inputStreamJar",
                                entryName, url),e);
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Throwable t) {
                                ExceptionUtils.handleThrowable(t);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error(sm.getString("contextConfig.jarFile", url), e);
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
            }
        }
    }

    
    protected void processAnnotationsJndi(URL url, WebXml fragment) {
        try {
            URLConnection urlConn = url.openConnection();
            DirContextURLConnection dcUrlConn;
            if (!(urlConn instanceof DirContextURLConnection)) {
                // This should never happen
                sm.getString("contextConfig.jndiUrlNotDirContextConn", url);
                return;
            }
            
            dcUrlConn = (DirContextURLConnection) urlConn;
            dcUrlConn.setUseCaches(false);
            
            String type = dcUrlConn.getHeaderField(ResourceAttributes.TYPE);
            if (ResourceAttributes.COLLECTION_TYPE.equals(type)) {
                // Collection
                Enumeration<String> dirs = dcUrlConn.list();
                while (dirs.hasMoreElements()) {
                    String dir = dirs.nextElement();
                    URL dirUrl = new URL(url.toString() + '/' + dir);
                    processAnnotationsJndi(dirUrl, fragment);
                }
                
            } else {
                // Single file
                if (url.getPath().endsWith(".class")) {
                    InputStream is = null;
                    try {
                        is = dcUrlConn.getInputStream();
                        processAnnotationsStream(is, fragment);
                    } catch (IOException e) {
                        log.error(sm.getString("contextConfig.inputStreamJndi",
                                url),e);
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Throwable t) {
                                ExceptionUtils.handleThrowable(t);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error(sm.getString("contextConfig.jndiUrl", url), e);
        }
    }
    
    
    protected void processAnnotationsFile(File file, WebXml fragment) {
        
        if (file.isDirectory()) {
            String[] dirs = file.list();
            for (String dir : dirs) {
                processAnnotationsFile(new File(file,dir), fragment);
            }
        } else if (file.canRead() && file.getName().endsWith(".class")) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                processAnnotationsStream(fis, fragment);
            } catch (IOException e) {
                log.error(sm.getString("contextConfig.inputStreamFile",
                        file.getAbsolutePath()),e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
            }
        }
    }


    protected void processAnnotationsStream(InputStream is, WebXml fragment)
            throws ClassFormatException, IOException {
        
        ClassParser parser = new ClassParser(is, null);
        JavaClass clazz = parser.parse();
        
        checkHandlesTypes(clazz);
        
        String className = clazz.getClassName();
        
        AnnotationEntry[] annotationsEntries = clazz.getAnnotationEntries();

        for (AnnotationEntry ae : annotationsEntries) {
            String type = ae.getAnnotationType();
            if ("Ljavax/servlet/annotation/WebServlet;".equals(type)) {
                processAnnotationWebServlet(className, ae, fragment);
            }else if ("Ljavax/servlet/annotation/WebFilter;".equals(type)) {
                processAnnotationWebFilter(className, ae, fragment);
            }else if ("Ljavax/servlet/annotation/WebListener;".equals(type)) {
                fragment.addListener(className);
            } else {
                // Unknown annotation - ignore
            }
        }
    }

    /**
     * For classes packaged with the web application, the class and each
     * super class needs to be checked for a match with {@link HandlesTypes} or
     * for an annotation that matches {@link HandlesTypes}.
     * @param javaClass
     */
    protected void checkHandlesTypes(JavaClass javaClass) {
        
        // Skip this if we can
        if (typeInitializerMap.size() == 0)
            return;
        
        // No choice but to load the class
        String className = javaClass.getClassName();
        
        Class<?> clazz = null;
        try {
            clazz = context.getLoader().getClassLoader().loadClass(className);
        } catch (NoClassDefFoundError e) {
            log.debug(sm.getString("contextConfig.invalidSciHandlesTypes",
                    className), e);
            return;
        } catch (ClassNotFoundException e) {
            log.warn(sm.getString("contextConfig.invalidSciHandlesTypes",
                    className), e);
            return;
        } catch (ClassFormatError e) {
            log.warn(sm.getString("contextConfig.invalidSciHandlesTypes",
                    className), e);
            return;
        }

        if (clazz.isAnnotation()) {
            // Skip
            return;
        }
        
        boolean match = false;
        
        for (Map.Entry<Class<?>, Set<ServletContainerInitializer>> entry :
                typeInitializerMap.entrySet()) {
            if (entry.getKey().isAnnotation()) {
                AnnotationEntry[] annotationEntries = javaClass.getAnnotationEntries();
                for (AnnotationEntry annotationEntry : annotationEntries) {
                    if (entry.getKey().getName().equals(
                        getClassName(annotationEntry.getAnnotationType()))) {
                        match = true;
                        break;
                    }
                }
            } else if (entry.getKey().isAssignableFrom(clazz)) {
                match = true;
            }
            if (match) {
                for (ServletContainerInitializer sci : entry.getValue()) {
                    initializerClassMap.get(sci).add(clazz);
                }
            }
        }
    }

    private static final String getClassName(String internalForm) {
        if (!internalForm.startsWith("L")) {
            return internalForm;
        }
        
        // Assume starts with L, ends with ; and uses / rather than .
        return internalForm.substring(1,
                internalForm.length() - 1).replace('/', '.');
    }

    protected void processAnnotationWebServlet(String className,
            AnnotationEntry ae, WebXml fragment) {
        String servletName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("name".equals(name)) {
                servletName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (servletName == null) {
            // classname is default servletName as annotation has no name!
            servletName = className;
        }
        ServletDef servletDef = fragment.getServlets().get(servletName);
        
        boolean isWebXMLservletDef;
        if (servletDef == null) {
            servletDef = new ServletDef();
            servletDef.setServletName(servletName);
            servletDef.setServletClass(className);
            isWebXMLservletDef = false;
        } else {
            isWebXMLservletDef = true;
        }

        boolean urlPatternsSet = false;
        String[] urlPatterns = null;

        // ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatternsSet = true;
                urlPatterns = processAnnotationsStringArray(evp.getValue());
            } else if ("description".equals(name)) {
                if (servletDef.getDescription() == null) {
                    servletDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (servletDef.getDisplayName() == null) {
                    servletDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (servletDef.getLargeIcon() == null) {
                    servletDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (servletDef.getSmallIcon() == null) {
                    servletDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (servletDef.getAsyncSupported() == null) {
                    servletDef.setAsyncSupported(evp.getValue()
                            .stringifyValue());
                }
            } else if ("loadOnStartup".equals(name)) {
                if (servletDef.getLoadOnStartup() == null) {
                    servletDef
                            .setLoadOnStartup(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLservletDef) {
                    Map<String, String> webXMLInitParams = servletDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            servletDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        servletDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }
            }
        }
        if (!isWebXMLservletDef && urlPatterns != null) {
            fragment.addServlet(servletDef);
        }
        if (urlPatterns != null) {
            if (!fragment.getServletMappings().containsValue(servletName)) {
                for (String urlPattern : urlPatterns) {
                    fragment.addServletMapping(urlPattern, servletName);
                }
            }
        }

    }

    /**
     * process filter annotation and merge with existing one!
     * FIXME: refactoring method to long and has redundant subroutines with processAnnotationWebServlet!
     * @param className
     * @param ae
     * @param fragment
     */
    protected void processAnnotationWebFilter(String className,
            AnnotationEntry ae, WebXml fragment) {
        String filterName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        ElementValuePair[] evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("filterName".equals(name)) {
                filterName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (filterName == null) {
            // classname is default filterName as annotation has no name!
            filterName = className;
        }
        FilterDef filterDef = fragment.getFilters().get(filterName);
        FilterMap filterMap = new FilterMap();

        boolean isWebXMLfilterDef;
        if (filterDef == null) {
            filterDef = new FilterDef();
            filterDef.setFilterName(filterName);
            filterDef.setFilterClass(className);
            isWebXMLfilterDef = false;
        } else {
            isWebXMLfilterDef = true;
        }

        boolean urlPatternsSet = false;
        boolean dispatchTypesSet = false;
        String[] urlPatterns = null;

        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatterns = processAnnotationsStringArray(evp.getValue());
                urlPatternsSet = urlPatterns.length > 0;
                for (String urlPattern : urlPatterns) {
                    filterMap.addURLPattern(urlPattern);
                }
            } else if ("servletNames".equals(name)) {
                String[] servletNames = processAnnotationsStringArray(evp
                        .getValue());
                for (String servletName : servletNames) {
                    filterMap.addServletName(servletName);
                }
            } else if ("dispatcherTypes".equals(name)) {
                String[] dispatcherTypes = processAnnotationsStringArray(evp
                        .getValue());
                dispatchTypesSet = dispatcherTypes.length > 0;
                for (String dispatcherType : dispatcherTypes) {
                    filterMap.setDispatcher(dispatcherType);
                }
            } else if ("description".equals(name)) {
                if (filterDef.getDescription() == null) {
                    filterDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (filterDef.getDisplayName() == null) {
                    filterDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (filterDef.getLargeIcon() == null) {
                    filterDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (filterDef.getSmallIcon() == null) {
                    filterDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (filterDef.getAsyncSupported() == null) {
                    filterDef
                            .setAsyncSupported(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLfilterDef) {
                    Map<String, String> webXMLInitParams = filterDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            filterDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        filterDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }

            }
        }
        if (!isWebXMLfilterDef) {
            fragment.addFilter(filterDef);
            filterMap.setFilterName(filterName);
            fragment.addFilterMapping(filterMap);
        }
        if (urlPatternsSet || dispatchTypesSet) {
            Set<FilterMap> fmap = fragment.getFilterMappings();
            FilterMap descMap = null;
            for (FilterMap map : fmap) {
                if (filterName.equals(map.getFilterName())) {
                    descMap = map;
                    break;
                }
            }
            if (descMap != null) {
                String[] urlsPatterns = descMap.getURLPatterns();
                if (urlPatternsSet
                        && (urlsPatterns == null || urlsPatterns.length == 0)) {
                    for (String urlPattern : filterMap.getURLPatterns()) {
                        descMap.addURLPattern(urlPattern);
                    }
                }
                String[] dispatcherNames = descMap.getDispatcherNames();
                if (dispatchTypesSet
                        && (dispatcherNames == null || dispatcherNames.length == 0)) {
                    for (String dis : filterMap.getDispatcherNames()) {
                        descMap.setDispatcher(dis);
                    }
                }
            }
        }

    }

    protected String[] processAnnotationsStringArray(ElementValue ev) {
        ArrayList<String> values = new ArrayList<String>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                values.add(value.stringifyValue());
            }
        } else {
            values.add(ev.stringifyValue());
        }
        String[] result = new String[values.size()];
        return values.toArray(result);
    }
    
    protected Map<String,String> processAnnotationWebInitParams(
            ElementValue ev) {
        Map<String, String> result = new HashMap<String,String>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                if (value instanceof AnnotationElementValue) {
                    ElementValuePair[] evps = ((AnnotationElementValue)
                            value).getAnnotationEntry().getElementValuePairs();
                    String initParamName = null;
                    String initParamValue = null;
                    for (ElementValuePair evp : evps) {
                        if ("name".equals(evp.getNameString())) {
                            initParamName = evp.getValue().stringifyValue();
                        } else if ("value".equals(evp.getNameString())) {
                            initParamValue = evp.getValue().stringifyValue();
                        } else {
                            // Ignore
                        }
                    }
                    result.put(initParamName, initParamValue);
                }
            }
        }
        return result;
    }
    
    private class FragmentJarScannerCallback implements JarScannerCallback {

        private static final String FRAGMENT_LOCATION =
            "META-INF/web-fragment.xml";
        private Map<String,WebXml> fragments = new HashMap<String,WebXml>();
        
        @Override
        public void scan(JarURLConnection urlConn) throws IOException {
            
            JarFile jarFile = null;
            InputStream stream = null;
            WebXml fragment = new WebXml();

            try {
                urlConn.setUseCaches(false);
                jarFile = urlConn.getJarFile();
                JarEntry fragmentEntry =
                    jarFile.getJarEntry(FRAGMENT_LOCATION);
                if (fragmentEntry == null) {
                    // If there is no web.xml, normal JAR no impact on
                    // distributable
                    fragment.setDistributable(true);
                } else {
                    stream = jarFile.getInputStream(fragmentEntry);
                    InputSource source = new InputSource(
                            urlConn.getJarFileURL().toString() +
                            "!/" + FRAGMENT_LOCATION);
                    source.setByteStream(stream);
                    parseWebXml(source, fragment, true);
                }
            } finally {
                if (jarFile != null) {
                    try {
                        jarFile.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
                fragment.setURL(urlConn.getURL());
                if (fragment.getName() == null) {
                    fragment.setName(fragment.getURL().toString());
                }
                fragments.put(fragment.getName(), fragment);
            }
        }

        @Override
        public void scan(File file) throws IOException {

            InputStream stream = null;
            WebXml fragment = null;
            
            try {
                File fragmentFile = new File(file, FRAGMENT_LOCATION);
                if (fragmentFile.isFile()) {
                    stream = new FileInputStream(fragmentFile);
                    InputSource source =
                        new InputSource(fragmentFile.toURI().toURL().toString());
                    source.setByteStream(stream);
                    fragment = new WebXml();
                    parseWebXml(source, fragment, true);
                }
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
                if (fragment == null) {
                    fragments.put(file.toURI().toURL().toString(), fragment);
                } else {
                    fragment.setURL(file.toURI().toURL());
                    if (fragment.getName() == null) {
                        fragment.setName(fragment.getURL().toString());
                    }
                    fragments.put(fragment.getName(), fragment);
                }
            }
        }
        
        public Map<String,WebXml> getFragments() {
            return fragments;
        }
    }
}
