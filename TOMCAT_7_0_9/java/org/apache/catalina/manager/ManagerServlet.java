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


package org.apache.catalina.manager;


import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Locale;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Binding;
import javax.naming.NamingEnumeration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Container;
import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Manager;
import org.apache.catalina.Server;
import org.apache.catalina.Session;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.RequestUtil;
import org.apache.catalina.util.ServerInfo;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;


/**
 * Servlet that enables remote management of the web applications installed
 * within the same virtual host as this web application is.  Normally, this
 * functionality will be protected by a security constraint in the web
 * application deployment descriptor.  However, this requirement can be
 * relaxed during testing.
 * <p>
 * This servlet examines the value returned by <code>getPathInfo()</code>
 * and related query parameters to determine what action is being requested.
 * The following actions and parameters (starting after the servlet path)
 * are supported:
 * <ul>
 * <li><b>/deploy?config={config-url}</b> - Install and start a new
 *     web application, based on the contents of the context configuration
 *     file found at the specified URL.  The <code>docBase</code> attribute
 *     of the context configuration file is used to locate the actual
 *     WAR or directory containing the application.</li>
 * <li><b>/deploy?config={config-url}&war={war-url}/</b> - Install and start
 *     a new web application, based on the contents of the context
 *     configuration file found at <code>{config-url}</code>, overriding the
 *     <code>docBase</code> attribute with the contents of the web
 *     application archive found at <code>{war-url}</code>.</li>
 * <li><b>/deploy?path=/xxx&war={war-url}</b> - Install and start a new
 *     web application attached to context path <code>/xxx</code>, based
 *     on the contents of the web application archive found at the
 *     specified URL.</li>
 * <li><b>/list</b> - List the context paths of all currently installed web
 *     applications for this virtual host.  Each context will be listed with
 *     the following format <code>path:status:sessions</code>.
 *     Where path is the context path.  Status is either running or stopped.
 *     Sessions is the number of active Sessions.</li>
 * <li><b>/reload?path=/xxx</b> - Reload the Java classes and resources for
 *     the application at the specified path.</li>
 * <li><b>/resources?type=xxxx</b> - Enumerate the available global JNDI
 *     resources, optionally limited to those of the specified type
 *     (fully qualified Java class name), if available.</li>
 * <li><b>/serverinfo</b> - Display system OS and JVM properties.
 * <li><b>/sessions</b> - Deprecated. Use expire.
 * <li><b>/expire?path=/xxx</b> - List session idle timeinformation about the
 *     web application attached to context path <code>/xxx</code> for this
 *     virtual host.</li>
 * <li><b>/expire?path=/xxx&idle=mm</b> - Expire sessions
 *     for the context path <code>/xxx</code> which were idle for at
 *     least mm minutes.</li>
 * <li><b>/start?path=/xxx</b> - Start the web application attached to
 *     context path <code>/xxx</code> for this virtual host.</li>
 * <li><b>/stop?path=/xxx</b> - Stop the web application attached to
 *     context path <code>/xxx</code> for this virtual host.</li>
 * <li><b>/undeploy?path=/xxx</b> - Shutdown and remove the web application
 *     attached to context path <code>/xxx</code> for this virtual host,
 *     and remove the underlying WAR file or document base directory.
 *     (<em>NOTE</em> - This is only allowed if the WAR file or document
 *     base is stored in the <code>appBase</code> directory of this host,
 *     typically as a result of being placed there via the <code>/deploy</code>
 *     command.</li>
 * </ul>
 * <p>Use <code>path=/</code> for the ROOT context.</p>
 * <p>The syntax of the URL for a web application archive must conform to one
 * of the following patterns to be successfully deployed:</p>
 * <ul>
 * <li><b>file:/absolute/path/to/a/directory</b> - You can specify the absolute
 *     path of a directory that contains the unpacked version of a web
 *     application.  This directory will be attached to the context path you
 *     specify without any changes.</li>
 * <li><b>jar:file:/absolute/path/to/a/warfile.war!/</b> - You can specify a
 *     URL to a local web application archive file.  The syntax must conform to
 *     the rules specified by the <code>JarURLConnection</code> class for a
 *     reference to an entire JAR file.</li>
 * <li><b>jar:http://hostname:port/path/to/a/warfile.war!/</b> - You can specify
 *     a URL to a remote (HTTP-accessible) web application archive file.  The
 *     syntax must conform to the rules specified by the
 *     <code>JarURLConnection</code> class for a reference to an entire
 *     JAR file.</li>
 * </ul>
 * <p>
 * <b>NOTE</b> - Attempting to reload or remove the application containing
 * this servlet itself will not succeed.  Therefore, this servlet should
 * generally be deployed as a separate web application within the virtual host
 * to be managed.
 * <p>
 * The following servlet initialization parameters are recognized:
 * <ul>
 * <li><b>debug</b> - The debugging detail level that controls the amount
 *     of information that is logged by this servlet.  Default is zero.
 * </ul>
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Id: ManagerServlet.java 1071243 2011-02-16 13:43:39Z markt $
 */

public class ManagerServlet extends HttpServlet implements ContainerServlet {

    private static final long serialVersionUID = 1L;


    // ----------------------------------------------------- Instance Variables


    /**
     * Path where context descriptors should be deployed.
     */
    protected File configBase = null;


    /**
     * The Context container associated with our web application.
     */
    protected Context context = null;


    /**
     * The debugging detail level for this servlet.
     */
    protected int debug = 1;


    /**
     * File object representing the directory into which the deploy() command
     * will store the WAR and context configuration files that have been
     * uploaded.
     */
    protected File deployed = null;


    /**
     * Path used to store revisions of webapps.
     */
    protected File versioned = null;


    /**
     * Path used to store context descriptors.
     */
    protected File contextDescriptors = null;


    /**
     * The associated host.
     */
    protected Host host = null;

    
    /**
     * The host appBase.
     */
    protected File appBase = null;
    
    
    /**
     * MBean server.
     */
    protected MBeanServer mBeanServer = null;


    /**
     * The associated deployer ObjectName.
     */
    protected ObjectName oname = null;
    

    /**
     * The global JNDI <code>NamingContext</code> for this server,
     * if available.
     */
    protected javax.naming.Context global = null;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * The Wrapper container associated with this servlet.
     */
    protected Wrapper wrapper = null;


    // ----------------------------------------------- ContainerServlet Methods


    /**
     * Return the Wrapper with which we are associated.
     */
    @Override
    public Wrapper getWrapper() {

        return (this.wrapper);

    }


    /**
     * Set the Wrapper with which we are associated.
     *
     * @param wrapper The new wrapper
     */
    @Override
    public void setWrapper(Wrapper wrapper) {

        this.wrapper = wrapper;
        if (wrapper == null) {
            context = null;
            host = null;
            oname = null;
        } else {
            context = (Context) wrapper.getParent();
            host = (Host) context.getParent();
            Engine engine = (Engine) host.getParent();
            try {
                oname = new ObjectName(engine.getName() 
                        + ":type=Deployer,host=" + host.getName());
            } catch (Exception e) {
                // ?
            }
        }

        // Retrieve the MBean server
        mBeanServer = Registry.getRegistry(null, null).getMBeanServer();
        
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Finalize this servlet.
     */
    @Override
    public void destroy() {

        // No actions necessary

    }


    /**
     * Process a GET request for the specified resource.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    @Override
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response)
        throws IOException, ServletException {

        StringManager smClient = getStringManager(request);

        // Identify the request parameters that we need
        String command = request.getPathInfo();
        if (command == null)
            command = request.getServletPath();
        String config = request.getParameter("config");
        String path = request.getParameter("path");
        ContextName cn = null;
        if (path != null) {
            cn = new ContextName(path, request.getParameter("version"));
        }
        String type = request.getParameter("type");
        String war = request.getParameter("war");
        String tag = request.getParameter("tag");
        boolean update = false;
        if ((request.getParameter("update") != null) 
            && (request.getParameter("update").equals("true"))) {
            update = true;
        }

        // Prepare our output writer to generate the response message
        response.setContentType("text/plain; charset=" + Constants.CHARSET);
        PrintWriter writer = response.getWriter();

        // Process the requested command (note - "/deploy" is not listed here)
        if (command == null) {
            writer.println(smClient.getString("managerServlet.noCommand"));
        } else if (command.equals("/deploy")) {
            if (war != null || config != null) {
                deploy(writer, config, cn, war, update, smClient);
            } else {
                deploy(writer, cn, tag, smClient);
            }
        } else if (command.equals("/list")) {
            list(writer, smClient);
        } else if (command.equals("/reload")) {
            reload(writer, cn, smClient);
        } else if (command.equals("/resources")) {
            resources(writer, type, smClient);
        } else if (command.equals("/save")) {
            save(writer, path, smClient);
        } else if (command.equals("/serverinfo")) {
            serverinfo(writer, smClient);
        } else if (command.equals("/sessions")) {
            expireSessions(writer, cn, request, smClient);
        } else if (command.equals("/expire")) {
            expireSessions(writer, cn, request, smClient);
        } else if (command.equals("/start")) {
            start(writer, cn, smClient);
        } else if (command.equals("/stop")) {
            stop(writer, cn, smClient);
        } else if (command.equals("/undeploy")) {
            undeploy(writer, cn, smClient);
        } else if (command.equals("/findleaks")) {
            findleaks(writer, smClient);
        } else {
            writer.println(smClient.getString("managerServlet.unknownCommand",
                    command));
        }

        // Finish up the response
        writer.flush();
        writer.close();

    }


    /**
     * Process a PUT request for the specified resource.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet-specified error occurs
     */
    @Override
    public void doPut(HttpServletRequest request,
                      HttpServletResponse response)
        throws IOException, ServletException {

        StringManager smClient = getStringManager(request);

        // Identify the request parameters that we need
        String command = request.getPathInfo();
        if (command == null)
            command = request.getServletPath();
        String path = request.getParameter("path");
        ContextName cn = null;
        if (path != null) {
            cn = new ContextName(path, request.getParameter("version"));
        }
        String tag = request.getParameter("tag");
        boolean update = false;
        if ((request.getParameter("update") != null) 
            && (request.getParameter("update").equals("true"))) {
            update = true;
        }

        // Prepare our output writer to generate the response message
        response.setContentType("text/plain;charset="+Constants.CHARSET);
        PrintWriter writer = response.getWriter();

        // Process the requested command
        if (command == null) {
            writer.println(smClient.getString("managerServlet.noCommand"));
        } else if (command.equals("/deploy")) {
            deploy(writer, cn, tag, update, request, smClient);
        } else {
            writer.println(smClient.getString("managerServlet.unknownCommand",
                    command));
        }

        // Finish up the response
        writer.flush();
        writer.close();

    }


    /**
     * Initialize this servlet.
     */
    @Override
    public void init() throws ServletException {

        // Ensure that our ContainerServlet properties have been set
        if ((wrapper == null) || (context == null))
            throw new UnavailableException(
                    sm.getString("managerServlet.noWrapper"));

        // Set our properties from the initialization parameters
        String value = null;
        try {
            value = getServletConfig().getInitParameter("debug");
            debug = Integer.parseInt(value);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
        }

        // Acquire global JNDI resources if available
        Server server = ((Engine)host.getParent()).getService().getServer();
        if ((server != null) && (server instanceof StandardServer)) {
            global = ((StandardServer) server).getGlobalNamingContext();
        }

        // Calculate the directory into which we will be deploying applications
        versioned = (File) getServletContext().getAttribute
            (ServletContext.TEMPDIR);

        // Identify the appBase of the owning Host of this Context
        // (if any)
        String appBase = ((Host) context.getParent()).getAppBase();
        deployed = new File(appBase);
        if (!deployed.isAbsolute()) {
            deployed = new File(System.getProperty(Globals.CATALINA_BASE_PROP),
                                appBase);
        }
        configBase = new File(System.getProperty(Globals.CATALINA_BASE_PROP), "conf");
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
            configBase = new File(configBase, engine.getName());
        }
        if (host != null) {
            configBase = new File(configBase, host.getName());
        }
        // Note: The directory must exist for this to work.

        // Log debugging messages as necessary
        if (debug >= 1) {
            log("init: Associated with Deployer '" +
                oname + "'");
            if (global != null) {
                log("init: Global resources are available");
            }
        }

    }



    // -------------------------------------------------------- Private Methods


    /**
     * Find potential memory leaks caused by web application reload.
     */
    protected void findleaks(PrintWriter writer, StringManager smClient) {
        
        if (!(host instanceof StandardHost)) {
            writer.println(smClient.getString("managerServlet.findleaksFail"));
            return;
        }
        
        String[] results =
            ((StandardHost) host).findReloadedContextMemoryLeaks();
        
        for (String result : results) {
            if ("".equals(result)) {
                result = "/";
            }
            writer.println(result);
        }
    }
    
    
    /**
     * Store server configuration.
     * 
     * @param path Optional context path to save
     */
    protected synchronized void save(PrintWriter writer, String path,
            StringManager smClient) {

        Server server = ((Engine)host.getParent()).getService().getServer();

        if (!(server instanceof StandardServer)) {
            writer.println(smClient.getString("managerServlet.saveFail",
                    server));
            return;
        }

        if ((path == null) || path.length() == 0 || !path.startsWith("/")) {
            try {
                ((StandardServer) server).storeConfig();
                writer.println(smClient.getString("managerServlet.saved"));
            } catch (Exception e) {
                log("managerServlet.storeConfig", e);
                writer.println(smClient.getString("managerServlet.exception",
                        e.toString()));
                return;
            }
        } else {
            String contextPath = path;
            if (path.equals("/")) {
                contextPath = "";
            }
            Context context = (Context) host.findChild(contextPath);
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext",
                        path));
                return;
            }
            try {
                ((StandardServer) server).storeContext(context);
                writer.println(smClient.getString("managerServlet.savedContext",
                        path));
            } catch (Exception e) {
                log("managerServlet.save[" + path + "]", e);
                writer.println(smClient.getString("managerServlet.exception",
                        e.toString()));
                return;
            }
        }

    }


    /**
     * Deploy a web application archive (included in the current request)
     * at the specified context path.
     *
     * @param writer Writer to render results to
     * @param cn Name of the application to be installed
     * @param tag Tag to be associated with the webapp
     * @param request Servlet request we are processing
     */
    protected synchronized void deploy
        (PrintWriter writer, ContextName cn,
         String tag, boolean update, HttpServletRequest request,
         StringManager smClient) {

        if (debug >= 1) {
            log("deploy: Deploying web application '" + cn + "'");
        }

        // Validate the requested context path
        if (!validateContextName(cn, writer, smClient)) {
            return;
        }
        String name = cn.getName();
        String baseName = cn.getBaseName();
        String displayPath = cn.getDisplayName();

        // Check if app already exists, or undeploy it if updating
        Context context = (Context) host.findChild(name);
        if (update) {
            if (context != null) {
                undeploy(writer, cn, smClient);
            }
            context = (Context) host.findChild(name);
        }
        if (context != null) {
            writer.println(smClient.getString("managerServlet.alreadyContext",
                    displayPath));
            return;
        }

        // Calculate the base path
        File deployedPath = deployed;
        if (tag != null) {
            deployedPath = new File(versioned, tag);
            deployedPath.mkdirs();
        }

        // Upload the web application archive to a local WAR file
        File localWar = new File(deployedPath, baseName + ".war");
        if (debug >= 2) {
            log("Uploading WAR file to " + localWar);
        }

        // Copy WAR to appBase
        try {
            if (!isServiced(name)) {
                addServiced(name);
                try {
                    // Upload WAR
                    uploadWar(request, localWar);
                    // Copy WAR and XML to the host app base if needed
                    if (tag != null) {
                        deployedPath = deployed;
                        File localWarCopy = new File(deployedPath, baseName + ".war");
                        copy(localWar, localWarCopy);
                        localWar = localWarCopy;
                        copy(localWar, new File(getAppBase(), baseName + ".war"));
                    }
                    // Perform new deployment
                    check(name);
                } finally {
                    removeServiced(name);
                }
            }
        } catch (Exception e) {
            log("managerServlet.check[" + displayPath + "]", e);
            writer.println(smClient.getString("managerServlet.exception",
                    e.toString()));
            return;
        }
        
        context = (Context) host.findChild(name);
        if (context != null && context.getConfigured()) {
            writer.println(smClient.getString(
                    "managerServlet.deployed", displayPath));
        } else {
            // Something failed
            writer.println(smClient.getString(
                    "managerServlet.deployFailed", displayPath));
        }
        
    }


    /**
     * Install an application for the specified path from the specified
     * web application archive.
     *
     * @param writer Writer to render results to
     * @param tag Revision tag to deploy from
     * @param cn Name of the application to be installed
     */
    protected void deploy(PrintWriter writer, ContextName cn, String tag,
            StringManager smClient) {

        // Validate the requested context path
        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        String baseName = cn.getBaseName();
        String name = cn.getName();
        String displayPath = cn.getDisplayName();
        
        // Calculate the base path
        File deployedPath = versioned;
        if (tag != null) {
            deployedPath = new File(deployedPath, tag);
        }

        // Find the local WAR file
        File localWar = new File(deployedPath, baseName + ".war");

        // Check if app already exists, or undeploy it if updating
        Context context = (Context) host.findChild(name);
        if (context != null) {
            undeploy(writer, cn, smClient);
        }

        // Copy WAR to appBase
        try {
            if (!isServiced(name)) {
                addServiced(name);
                try {
                    copy(localWar, new File(getAppBase(), baseName + ".war"));
                    // Perform new deployment
                    check(name);
                } finally {
                    removeServiced(name);
                }
            }
        } catch (Exception e) {
            log("managerServlet.check[" + displayPath + "]", e);
            writer.println(smClient.getString("managerServlet.exception",
                    e.toString()));
            return;
        }
        
        context = (Context) host.findChild(name);
        if (context != null && context.getConfigured()) {
            writer.println(smClient.getString("managerServlet.deployed",
                    displayPath));
        } else {
            // Something failed
            writer.println(smClient.getString("managerServlet.deployFailed",
                    displayPath));
        }
        
    }


    /**
     * Install an application for the specified path from the specified
     * web application archive.
     *
     * @param writer Writer to render results to
     * @param config URL of the context configuration file to be installed
     * @param cn Name of the application to be installed
     * @param war URL of the web application archive to be installed
     * @param update true to override any existing webapp on the path
     */
    protected void deploy(PrintWriter writer, String config, ContextName cn,
            String war, boolean update,  StringManager smClient) {
        
        if (config != null && config.length() == 0) {
            config = null;
        }
        if (war != null && war.length() == 0) {
            war = null;
        }
        
        if (debug >= 1) {
            if (config != null && config.length() > 0) {
                if (war != null) {
                    log("install: Installing context configuration at '" +
                            config + "' from '" + war + "'");
                } else {
                    log("install: Installing context configuration at '" +
                            config + "'");
                }
            } else {
                if (cn != null) {
                    log("install: Installing web application '" + cn +
                            "' from '" + war + "'");
                } else {
                    log("install: Installing web application from '" + war + "'");
                }
            }
        }
        
        if (!validateContextName(cn, writer, smClient)) {
            return;
        }
        String name = cn.getName();
        String baseName = cn.getBaseName();
        String displayPath = cn.getDisplayName();
        
        // Check if app already exists, or undeploy it if updating
        Context context = (Context) host.findChild(name);
        if (update) {
            if (context != null) {
                undeploy(writer, cn, smClient);
            }
            context = (Context) host.findChild(name);
        }
        if (context != null) {
            writer.println(smClient.getString("managerServlet.alreadyContext",
                    displayPath));
            return;
        }
        
        if (config != null && (config.startsWith("file:"))) {
            config = config.substring("file:".length());
        }
        if (war != null && (war.startsWith("file:"))) {
            war = war.substring("file:".length());
        }
        
        try {
            if (!isServiced(name)) {
                addServiced(name);
                try {
                    if (config != null) {
                        configBase.mkdirs();
                        copy(new File(config), 
                                new File(configBase, baseName + ".xml"));
                    }
                    if (war != null) {
                        if (war.endsWith(".war")) {
                            copy(new File(war), 
                                    new File(getAppBase(), baseName + ".war"));
                        } else {
                            copy(new File(war), 
                                    new File(getAppBase(), baseName));
                        }
                    }
                    // Perform new deployment
                    check(name);
                } finally {
                    removeServiced(name);
                }
            }
            context = (Context) host.findChild(name);
            if (context != null && context.getConfigured() && context.getAvailable()) {
                writer.println(smClient.getString(
                        "managerServlet.deployed", displayPath));
            } else if (context!=null && !context.getAvailable()) {
                writer.println(smClient.getString(
                        "managerServlet.deployedButNotStarted", displayPath));
            } else {
                // Something failed
                writer.println(smClient.getString(
                        "managerServlet.deployFailed", displayPath));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.install[" + displayPath + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }
        
    }


    /**
     * Render a list of the currently active Contexts in our virtual host.
     *
     * @param writer Writer to render to
     */
    protected void list(PrintWriter writer, StringManager smClient) {

        if (debug >= 1)
            log("list: Listing contexts for virtual host '" +
                host.getName() + "'");

        writer.println(smClient.getString("managerServlet.listed",
                                    host.getName()));
        Container[] contexts = host.findChildren();
        for (int i = 0; i < contexts.length; i++) {
            Context context = (Context) contexts[i];
            if (context != null ) {
                String displayPath = context.getPath();
                if( displayPath.equals("") )
                    displayPath = "/";
                if (context.getAvailable()) {
                    writer.println(smClient.getString("managerServlet.listitem",
                            displayPath,
                            "running",
                            "" + context.getManager().findSessions().length,
                            context.getDocBase()));
                } else {
                    writer.println(smClient.getString("managerServlet.listitem",
                            displayPath,
                            "stopped",
                            "0",
                            context.getDocBase()));
                }
            }
        }
    }


    /**
     * Reload the web application at the specified context path.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to be restarted
     */
    protected void reload(PrintWriter writer, ContextName cn,
            StringManager smClient) {

        if (debug >= 1)
            log("restart: Reloading web application '" + cn + "'");

        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        try {
            Context context = (Context) host.findChild(cn.getName());
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext",
                        RequestUtil.filter(cn.getDisplayName())));
                return;
            }
            // It isn't possible for the manager to reload itself
            if (context.getName().equals(this.context.getName())) {
                writer.println(smClient.getString("managerServlet.noSelf"));
                return;
            }
            context.reload();
            writer.println(smClient.getString("managerServlet.reloaded",
                    cn.getDisplayName()));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.reload[" + cn.getDisplayName() + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    /**
     * Render a list of available global JNDI resources.
     *
     * @param type Fully qualified class name of the resource type of interest,
     *  or <code>null</code> to list resources of all types
     */
    protected void resources(PrintWriter writer, String type,
            StringManager smClient) {

        if (debug >= 1) {
            if (type != null) {
                log("resources:  Listing resources of type " + type);
            } else {
                log("resources:  Listing resources of all types");
            }
        }

        // Is the global JNDI resources context available?
        if (global == null) {
            writer.println(smClient.getString("managerServlet.noGlobal"));
            return;
        }

        // Enumerate the global JNDI resources of the requested type
        if (type != null) {
            writer.println(smClient.getString("managerServlet.resourcesType",
                    type));
        } else {
            writer.println(smClient.getString("managerServlet.resourcesAll"));
        }

        Class<?> clazz = null;
        try {
            if (type != null) {
                clazz = Class.forName(type);
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.resources[" + type + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
            return;
        }

        printResources(writer, "", global, type, clazz, smClient);

    }


    /**
     * List the resources of the given context.
     */
    protected void printResources(PrintWriter writer, String prefix,
                                  javax.naming.Context namingContext,
                                  String type, Class<?> clazz,
                                  StringManager smClient) {

        try {
            NamingEnumeration<Binding> items = namingContext.listBindings("");
            while (items.hasMore()) {
                Binding item = items.next();
                if (item.getObject() instanceof javax.naming.Context) {
                    printResources
                        (writer, prefix + item.getName() + "/",
                         (javax.naming.Context) item.getObject(), type, clazz,
                         smClient);
                } else {
                    if ((clazz != null) &&
                        (!(clazz.isInstance(item.getObject())))) {
                        continue;
                    }
                    writer.print(prefix + item.getName());
                    writer.print(':');
                    writer.print(item.getClassName());
                    // Do we want a description if available?
                    writer.println();
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.resources[" + type + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    /**
     * Writes System OS and JVM properties.
     * @param writer Writer to render to
     */
    protected void serverinfo(PrintWriter writer,  StringManager smClient) {
        if (debug >= 1)
            log("serverinfo");
        try {
            StringBuilder props = new StringBuilder();
            props.append("OK - Server info");
            props.append("\nTomcat Version: ");
            props.append(ServerInfo.getServerInfo());
            props.append("\nOS Name: ");
            props.append(System.getProperty("os.name"));
            props.append("\nOS Version: ");
            props.append(System.getProperty("os.version"));
            props.append("\nOS Architecture: ");
            props.append(System.getProperty("os.arch"));
            props.append("\nJVM Version: ");
            props.append(System.getProperty("java.runtime.version"));
            props.append("\nJVM Vendor: ");
            props.append(System.getProperty("java.vm.vendor"));
            writer.println(props.toString());
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getServletContext().log("ManagerServlet.serverinfo",t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }
    }

    /**
     * Session information for the web application at the specified context path.
     * Displays a profile of session thisAccessedTime listing number
     * of sessions for each 10 minute interval up to 10 hours.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to list session information for
     * @param idle Expire all sessions with idle time &gt; idle for this context
     */
    protected void sessions(PrintWriter writer, ContextName cn, int idle,
            StringManager smClient) {

        if (debug >= 1) {
            log("sessions: Session information for web application '" + cn + "'");
            if (idle >= 0)
                log("sessions: Session expiration for " + idle + " minutes '" + cn + "'");
        }

        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        String displayPath = cn.getDisplayName();

        try {
            Context context = (Context) host.findChild(cn.getName());
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext",
                        RequestUtil.filter(displayPath)));
                return;
            }
            Manager manager = context.getManager() ;
            if(manager == null) {
                writer.println(smClient.getString("managerServlet.noManager",
                        RequestUtil.filter(displayPath)));
                return;               
            }
            int maxCount = 60;
            int maxInactiveInterval = manager.getMaxInactiveInterval()/60;
            int histoInterval = maxInactiveInterval / maxCount;
            if ( histoInterval * maxCount < maxInactiveInterval ) 
                histoInterval++;
            if (0==histoInterval)
                histoInterval=1;
            maxCount = maxInactiveInterval / histoInterval;
            if ( histoInterval * maxCount < maxInactiveInterval ) 
                maxCount++;

            writer.println(smClient.getString("managerServlet.sessions",
                    displayPath));
            writer.println(smClient.getString(
                    "managerServlet.sessiondefaultmax",
                    "" + maxInactiveInterval));
            Session [] sessions = manager.findSessions();
            int [] timeout = new int[maxCount];
            int notimeout = 0;
            int expired = 0;
            long now = System.currentTimeMillis();
            for (int i = 0; i < sessions.length; i++) {
                int time = (int)((now-sessions[i].getThisAccessedTimeInternal())/1000);
                if (idle >= 0 && time >= idle*60) {
                    sessions[i].expire();
                    expired++;
                }
                time=time/60/histoInterval;
                if (time < 0)
                    notimeout++;
                else if (time >= maxCount)
                    timeout[maxCount-1]++;
                else
                    timeout[time]++;
            }
            if (timeout[0] > 0)
                writer.println(smClient.getString(
                        "managerServlet.sessiontimeout",
                        "<" + histoInterval, "" + timeout[0]));
            for (int i = 1; i < maxCount-1; i++) {
                if (timeout[i] > 0)
                    writer.println(smClient.getString(
                            "managerServlet.sessiontimeout",
                            "" + (i)*histoInterval + " - <" + (i+1)*histoInterval,
                            "" + timeout[i]));
            }
            if (timeout[maxCount-1] > 0)
                writer.println(smClient.getString(
                        "managerServlet.sessiontimeout",
                        ">=" + maxCount*histoInterval,
                        "" + timeout[maxCount-1]));
            if (notimeout > 0)
                writer.println(smClient.getString(
                        "managerServlet.sessiontimeout.unlimited",
                        "" + notimeout));
            if (idle >= 0)
                writer.println(smClient.getString(
                        "managerServlet.sessiontimeout.expired",
                        "" + idle,"" + expired));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.sessions[" + displayPath + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    /**
     * Session information for the web application at the specified context path.
     * Displays a profile of session thisAccessedTime listing number
     * of sessions for each 10 minute interval up to 10 hours.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to list session information for
     */
    protected void sessions(PrintWriter writer, ContextName cn,
            StringManager smClient) {
        sessions(writer, cn, -1, smClient);
    }


    /**
     *
     * Extract the expiration request parameter
     *
     * @param cn
     * @param req
     */
    protected void expireSessions(PrintWriter writer, ContextName cn,
            HttpServletRequest req, StringManager smClient) {
        int idle = -1;
        String idleParam = req.getParameter("idle");
        if (idleParam != null) {
            try {
                idle = Integer.parseInt(idleParam);
            } catch (NumberFormatException e) {
                log("Could not parse idle parameter to an int: " + idleParam);
            }
        }
        sessions(writer, cn, idle, smClient);
    }

    /**
     * Start the web application at the specified context path.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to be started
     */
    protected void start(PrintWriter writer, ContextName cn,
            StringManager smClient) {

        if (debug >= 1)
            log("start: Starting web application '" + cn + "'");

        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        String displayPath = cn.getDisplayName();

        try {
            Context context = (Context) host.findChild(cn.getName());
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext", 
                        RequestUtil.filter(displayPath)));
                return;
            }
            context.start();
            if (context.getAvailable())
                writer.println(smClient.getString("managerServlet.started",
                        displayPath));
            else
                writer.println(smClient.getString("managerServlet.startFailed",
                        displayPath));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            getServletContext().log(sm.getString("managerServlet.startFailed",
                    displayPath), t);
            writer.println(smClient.getString("managerServlet.startFailed",
                    displayPath));
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    /**
     * Stop the web application at the specified context path.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to be stopped
     */
    protected void stop(PrintWriter writer, ContextName cn,
            StringManager smClient) {

        if (debug >= 1)
            log("stop: Stopping web application '" + cn + "'");

        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        String displayPath = cn.getDisplayName();

        try {
            Context context = (Context) host.findChild(cn.getName());
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext",
                        RequestUtil.filter(displayPath)));
                return;
            }
            // It isn't possible for the manager to stop itself
            if (context.getName().equals(this.context.getName())) {
                writer.println(smClient.getString("managerServlet.noSelf"));
                return;
            }
            context.stop();
            writer.println(smClient.getString(
                    "managerServlet.stopped", displayPath));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.stop[" + displayPath + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    /**
     * Undeploy the web application at the specified context path.
     *
     * @param writer Writer to render to
     * @param cn Name of the application to be removed
     */
    protected void undeploy(PrintWriter writer, ContextName cn,
            StringManager smClient) {

        if (debug >= 1)
            log("undeploy: Undeploying web application at '" + cn + "'");

        if (!validateContextName(cn, writer, smClient)) {
            return;
        }

        String name = cn.getName();
        String baseName = cn.getBaseName();
        String displayPath = cn.getDisplayName();

        try {

            // Validate the Context of the specified application
            Context context = (Context) host.findChild(name);
            if (context == null) {
                writer.println(smClient.getString("managerServlet.noContext",
                        RequestUtil.filter(displayPath)));
                return;
            }

            if (!isDeployed(name)) {
                writer.println(smClient.getString("managerServlet.notDeployed",
                        RequestUtil.filter(displayPath)));
                return;
            }

            if (!isServiced(name)) {
                addServiced(name);
                try {
                    // Try to stop the context first to be nicer
                    context.stop();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
                try {
                    File war = new File(getAppBase(), baseName + ".war");
                    File dir = new File(getAppBase(), baseName);
                    File xml = new File(configBase, baseName + ".xml");
                    if (war.exists()) {
                        war.delete();
                    } else if (dir.exists()) {
                        undeployDir(dir);
                    } else {
                        xml.delete();
                    }
                    // Perform new deployment
                    check(name);
                } finally {
                    removeServiced(name);
                }
            }
            writer.println(smClient.getString("managerServlet.undeployed",
                    displayPath));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log("ManagerServlet.undeploy[" + displayPath + "]", t);
            writer.println(smClient.getString("managerServlet.exception",
                    t.toString()));
        }

    }


    // -------------------------------------------------------- Support Methods


    /**
     * Return a File object representing the "application root" directory
     * for our associated Host.
     */
    protected File getAppBase() {

        if (appBase != null) {
            return appBase;
        }

        File file = new File(host.getAppBase());
        if (!file.isAbsolute())
            file = new File(System.getProperty(Globals.CATALINA_BASE_PROP),
                            host.getAppBase());
        try {
            appBase = file.getCanonicalFile();
        } catch (IOException e) {
            appBase = file;
        }
        return (appBase);

    }


    /**
     * Invoke the isDeployed method on the deployer.
     */
    protected boolean isDeployed(String name) 
        throws Exception {
        String[] params = { name };
        String[] signature = { "java.lang.String" };
        Boolean result = 
            (Boolean) mBeanServer.invoke(oname, "isDeployed", params, signature);
        return result.booleanValue();
    }
    

    /**
     * Invoke the check method on the deployer.
     */
    protected void check(String name) 
        throws Exception {
        String[] params = { name };
        String[] signature = { "java.lang.String" };
        mBeanServer.invoke(oname, "check", params, signature);
    }
    

    /**
     * Invoke the isServiced method on the deployer.
     */
    protected boolean isServiced(String name) 
        throws Exception {
        String[] params = { name };
        String[] signature = { "java.lang.String" };
        Boolean result = 
            (Boolean) mBeanServer.invoke(oname, "isServiced", params, signature);
        return result.booleanValue();
    }
    

    /**
     * Invoke the addServiced method on the deployer.
     */
    protected void addServiced(String name) 
        throws Exception {
        String[] params = { name };
        String[] signature = { "java.lang.String" };
        mBeanServer.invoke(oname, "addServiced", params, signature);
    }
    

    /**
     * Invoke the removeServiced method on the deployer.
     */
    protected void removeServiced(String name) 
        throws Exception {
        String[] params = { name };
        String[] signature = { "java.lang.String" };
        mBeanServer.invoke(oname, "removeServiced", params, signature);
    }
    

    /**
     * Delete the specified directory, including all of its contents and
     * subdirectories recursively.
     *
     * @param dir File object representing the directory to be deleted
     */
    protected void undeployDir(File dir) {

        String files[] = dir.list();
        if (files == null) {
            files = new String[0];
        }
        for (int i = 0; i < files.length; i++) {
            File file = new File(dir, files[i]);
            if (file.isDirectory()) {
                undeployDir(file);
            } else {
                file.delete();
            }
        }
        dir.delete();

    }


    /**
     * Upload the WAR file included in this request, and store it at the
     * specified file location.
     *
     * @param request The servlet request we are processing
     * @param war The file into which we should store the uploaded WAR
     *
     * @exception IOException if an I/O error occurs during processing
     */
    protected void uploadWar(HttpServletRequest request, File war)
        throws IOException {

        war.delete();
        ServletInputStream istream = null;
        BufferedOutputStream ostream = null;
        try {
            istream = request.getInputStream();
            ostream =
                new BufferedOutputStream(new FileOutputStream(war), 1024);
            byte buffer[] = new byte[1024];
            while (true) {
                int n = istream.read(buffer);
                if (n < 0) {
                    break;
                }
                ostream.write(buffer, 0, n);
            }
            ostream.flush();
            ostream.close();
            ostream = null;
            istream.close();
            istream = null;
        } catch (IOException e) {
            war.delete();
            throw e;
        } finally {
            if (ostream != null) {
                try {
                    ostream.close();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
                ostream = null;
            }
            if (istream != null) {
                try {
                    istream.close();
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                }
                istream = null;
            }
        }

    }


    protected StringManager getStringManager(HttpServletRequest req) {
        Enumeration<Locale> requestedLocales = req.getLocales();
        while (requestedLocales.hasMoreElements()) {
            Locale locale = requestedLocales.nextElement();
            StringManager result = StringManager.getManager(Constants.Package,
                    locale);
            if (result.getLocale().equals(locale)) {
                return result;
            }
        }
        // Return the default
        return sm;
    }


    protected static boolean validateContextName(ContextName cn,
            PrintWriter writer, StringManager sm) {
        
        // ContextName should be non-null with a path that is empty or starts
        // with /
        if (cn != null &&
                (cn.getPath().startsWith("/") || cn.getPath().equals(""))) {
            return true;
        }
        
        String path = null;
        if (cn != null) {
            path = RequestUtil.filter(cn.getPath());
        }
        writer.println(sm.getString("managerServlet.invalidPath", path));
        return false;
    }

    /**
     * Copy the specified file or directory to the destination.
     *
     * @param src File object representing the source
     * @param dest File object representing the destination
     */
    public static boolean copy(File src, File dest) {
        boolean result = false;
        try {
            if( src != null &&
                    !src.getCanonicalPath().equals(dest.getCanonicalPath()) ) {
                result = copyInternal(src, dest, new byte[4096]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    
    /**
     * Copy the specified file or directory to the destination.
     *
     * @param src File object representing the source
     * @param dest File object representing the destination
     */
    public static boolean copyInternal(File src, File dest, byte[] buf) {
        
        boolean result = true;
        
        String files[] = null;
        if (src.isDirectory()) {
            files = src.list();
            result = dest.mkdir();
        } else {
            files = new String[1];
            files[0] = "";
        }
        if (files == null) {
            files = new String[0];
        }
        for (int i = 0; (i < files.length) && result; i++) {
            File fileSrc = new File(src, files[i]);
            File fileDest = new File(dest, files[i]);
            if (fileSrc.isDirectory()) {
                result = copyInternal(fileSrc, fileDest, buf);
            } else {
                FileInputStream is = null;
                FileOutputStream os = null;
                try {
                    is = new FileInputStream(fileSrc);
                    os = new FileOutputStream(fileDest);
                    int len = 0;
                    while (true) {
                        len = is.read(buf);
                        if (len == -1)
                            break;
                        os.write(buf, 0, len);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    result = false;
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                    if (os != null) {
                        try {
                            os.close();
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                }
            }
        }
        return result;
        
    }
    
    
}
