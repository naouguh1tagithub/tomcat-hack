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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.deploy.ContextEnvironment;
import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.realm.RealmBase;
import org.apache.tomcat.util.buf.ByteChunk;

public class TestTomcat extends TomcatBaseTest {

    /**
     * Simple servlet to test in-line registration 
     */
    public static class HelloWorld extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) 
                throws IOException {
            res.getWriter().write("Hello world");
        }
    }

    /**
     * Simple servlet to test JNDI 
     */
    public static class HelloWorldJndi extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private static final String JNDI_ENV_NAME = "test";
        
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) 
                throws IOException {
            
            String name = null;
            
            try {
                Context initCtx = new InitialContext();
                Context envCtx = (Context) initCtx.lookup("java:comp/env");
                name = (String) envCtx.lookup(JNDI_ENV_NAME);
            } catch (NamingException e) {
                throw new IOException(e);
            }
            
            res.getWriter().write("Hello, " + name);
        }
    }

    /**
     * Servlet that tries to obtain a URL for WEB-INF/web.xml
     */
    public static class GetResource extends HttpServlet {
        
        private static final long serialVersionUID = 1L;
        
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) 
        throws IOException {
            URL url = req.getServletContext().getResource("/WEB-INF/web.xml");
         
            res.getWriter().write("The URL obtained for /WEB-INF/web.xml was ");
            if (url == null) {
                res.getWriter().write("null");
            } else {
                res.getWriter().write(url.toString() + "\n");
                res.getWriter().write("The first 20 characters of that resource are:\n");

                // Read some content from the resource
                URLConnection conn = url.openConnection();
                
                InputStream is = null;
                Reader reader = null;
                char cbuf[] = new char[20];
                try {
                    is = conn.getInputStream();
                    reader = new InputStreamReader(is);
                    reader.read(cbuf);
                    res.getWriter().write(cbuf);
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch(IOException ioe) {/*Ignore*/}
                    }
                    if (is != null) {
                        try { is.close(); } catch(IOException ioe) {/*Ignore*/}
                    }
                }
                
                
            }
            
            
        }
    }
    
    /**
     * Simple Realm that uses a configurable {@link Map} to link user names and
     * passwords.
     */
    public static final class MapRealm extends RealmBase {
        private Map<String,String> users = new HashMap<String,String>();
        private Map<String,List<String>> roles =
            new HashMap<String,List<String>>();
        
        public void addUser(String username, String password) {
            users.put(username, password);
        }

        public void addUserRole(String username, String role) {
            List<String> userRoles = roles.get(username);
            if (userRoles == null) {
                userRoles = new ArrayList<String>();
                roles.put(username, userRoles);
            }
            userRoles.add(role);
        }

        @Override
        protected String getName() {
            return "MapRealm";
        }

        @Override
        protected String getPassword(String username) {
            return users.get(username);
        }

        @Override
        protected Principal getPrincipal(String username) {
            return new GenericPrincipal(username, getPassword(username),
                    roles.get(username));
        }
        
    }

    /** 
     * Start tomcat with a single context and one 
     * servlet - all programmatic, no server.xml or 
     * web.xml used.
     * 
     * @throws Exception 
     */
    public void testProgrammatic() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        
        // Must have a real docBase - just use temp
        org.apache.catalina.Context ctx = 
            tomcat.addContext("", System.getProperty("java.io.tmpdir"));
        // You can customize the context by calling 
        // its API
        
        Tomcat.addServlet(ctx, "myServlet", new HelloWorld());
        ctx.addServletMapping("/", "myServlet");
        
        tomcat.start();
        
        ByteChunk res = getUrl("http://localhost:" + getPort() + "/");
        assertEquals("Hello world", res.toString());
    }

    public void testSingleWebapp() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        File appDir = new File(getBuildDirectory(), "webapps/examples");
        // app dir is relative to server home
        tomcat.addWebapp(null, "/examples", appDir.getAbsolutePath());
        
        tomcat.start();

        ByteChunk res = getUrl("http://localhost:" + getPort() +
                "/examples/servlets/servlet/HelloWorldExample");
        assertTrue(res.toString().indexOf("<h1>Hello World!</h1>") > 0);
    }
    
    public void testJsps() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        File appDir = new File(getBuildDirectory(), "webapps/examples");
        // app dir is relative to server home
        tomcat.addWebapp(null, "/examples", appDir.getAbsolutePath());
        
        tomcat.start();

        ByteChunk res = getUrl("http://localhost:" + getPort() +
                "/examples/jsp/jsp2/el/basic-arithmetic.jsp");
        assertTrue(res.toString().indexOf("<td>${(1==2) ? 3 : 4}</td>") > 0);
    }
    
    public void testLaunchTime() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        long t0 = System.currentTimeMillis();
        tomcat.addContext(null, "/", ".");
        tomcat.start();
        System.err.println("Test time: " + 
                (System.currentTimeMillis() - t0));
     }

    
    /** 
     * Test for enabling JNDI.
     */
    public void testEnableNaming() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        
        // Must have a real docBase - just use temp
        org.apache.catalina.Context ctx = 
            tomcat.addContext("", System.getProperty("java.io.tmpdir"));
        
        // You can customise the context by calling its API
        
        // Enable JNDI - it is disabled by default
        tomcat.enableNaming();

        ContextEnvironment environment = new ContextEnvironment();
        environment.setType("java.lang.String");
        environment.setName(HelloWorldJndi.JNDI_ENV_NAME);
        environment.setValue("Tomcat User");
        ctx.getNamingResources().addEnvironment(environment);
        
        Tomcat.addServlet(ctx, "jndiServlet", new HelloWorldJndi());
        ctx.addServletMapping("/", "jndiServlet");
        
        tomcat.start();
        
        ByteChunk res = getUrl("http://localhost:" + getPort() + "/");
        assertEquals("Hello, Tomcat User", res.toString());
    }

    /** 
     * Test for enabling JNDI and using global resources.
     */
    public void testEnableNamingGlobal() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        
        // Must have a real docBase - just use temp
        org.apache.catalina.Context ctx = 
            tomcat.addContext("", System.getProperty("java.io.tmpdir"));
        
        // You can customise the context by calling its API
        
        // Enable JNDI - it is disabled by default
        tomcat.enableNaming();

        ContextEnvironment environment = new ContextEnvironment();
        environment.setType("java.lang.String");
        environment.setName("globalTest");
        environment.setValue("Tomcat User");
        tomcat.getServer().getGlobalNamingResources().addEnvironment(environment);
        
        ContextResourceLink link = new ContextResourceLink();
        link.setGlobal("globalTest");
        link.setName(HelloWorldJndi.JNDI_ENV_NAME);
        ctx.getNamingResources().addResourceLink(link);
        
        Tomcat.addServlet(ctx, "jndiServlet", new HelloWorldJndi());
        ctx.addServletMapping("/", "jndiServlet");
        
        tomcat.start();
        
        ByteChunk res = getUrl("http://localhost:" + getPort() + "/");
        assertEquals("Hello, Tomcat User", res.toString());
    }


    /**
     * Test for https://issues.apache.org/bugzilla/show_bug.cgi?id=47866
     */
    public void testGetResource() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        
        String contextPath = "/examples";
        
        File appDir = new File(getBuildDirectory(), "webapps" + contextPath);
        // app dir is relative to server home
        org.apache.catalina.Context ctx =
            tomcat.addWebapp(null, "/examples", appDir.getAbsolutePath());
        
        Tomcat.addServlet(ctx, "testGetResource", new GetResource());
        ctx.addServletMapping("/testGetResource", "testGetResource");
        
        tomcat.start();
        
        ByteChunk res = new ByteChunk();
        
        int rc =getUrl("http://localhost:" + getPort() + contextPath +
                "/testGetResource", res, null);
        assertEquals(HttpServletResponse.SC_OK, rc);
        assertTrue(res.toString().contains("<?xml version=\"1.0\" "));
    }

    public void testBug50826() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        String contextPath = "/examples";
        
        File appDir = new File(getBuildDirectory(), "webapps" + contextPath);
        // app dir is relative to server home
        tomcat.addWebapp(null, "/examples", appDir.getAbsolutePath());

        Exception e = null;
        try {
            tomcat.destroy();
        } catch (Exception ex) {
            ex.printStackTrace();
            e = ex;
        }
        assertNull(e);
    }
}
