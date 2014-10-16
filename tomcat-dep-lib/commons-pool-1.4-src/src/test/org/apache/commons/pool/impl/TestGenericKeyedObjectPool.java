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

package org.apache.commons.pool.impl;

import junit.framework.Test;
import junit.framework.TestSuite;
import org.apache.commons.pool.KeyedObjectPool;
import org.apache.commons.pool.KeyedPoolableObjectFactory;
import org.apache.commons.pool.TestBaseKeyedObjectPool;
import org.apache.commons.pool.VisitTracker;
import org.apache.commons.pool.VisitTrackerFactory;

import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * @author Rodney Waldhoff
 * @version $Revision: 606718 $ $Date: 2007-12-24 10:55:10 -0700 (Mon, 24 Dec 2007) $
 */
public class TestGenericKeyedObjectPool extends TestBaseKeyedObjectPool {
    public TestGenericKeyedObjectPool(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestGenericKeyedObjectPool.class);
    }

    protected KeyedObjectPool makeEmptyPool(int mincapacity) {
        GenericKeyedObjectPool pool = new GenericKeyedObjectPool(
            new KeyedPoolableObjectFactory()  {
                HashMap map = new HashMap();
                public Object makeObject(Object key) {
                    int counter = 0;
                    Integer Counter = (Integer)(map.get(key));
                    if(null != Counter) {
                        counter = Counter.intValue();
                    }
                    map.put(key,new Integer(counter + 1));
                    return String.valueOf(key) + String.valueOf(counter);
                }
                public void destroyObject(Object key, Object obj) { }
                public boolean validateObject(Object key, Object obj) { return true; }
                public void activateObject(Object key, Object obj) { }
                public void passivateObject(Object key, Object obj) { }
            }
        );
        pool.setMaxActive(mincapacity);
        pool.setMaxIdle(mincapacity);
        return pool;
    }

    protected KeyedObjectPool makeEmptyPool(KeyedPoolableObjectFactory factory) {
        return new GenericKeyedObjectPool(factory);
    }

    protected Object getNthObject(Object key, int n) {
        return String.valueOf(key) + String.valueOf(n);
    }

    protected Object makeKey(int n) {
        return String.valueOf(n);
    }

    private GenericKeyedObjectPool pool = null;
    private Integer zero = new Integer(0);
    private Integer one = new Integer(1);
    private Integer two = new Integer(2);

    public void setUp() throws Exception {
        super.setUp();
        pool = new GenericKeyedObjectPool(new SimpleFactory());
    }

    public void tearDown() throws Exception {
        super.tearDown();
        pool.close();
        pool = null;
    }

    public void testNegativeMaxActive() throws Exception {
        pool.setMaxActive(-1);
        pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_FAIL);
        Object obj = pool.borrowObject("");
        assertEquals("0",obj);
        pool.returnObject("",obj);
    }

    public void testNumActiveNumIdle2() throws Exception {
        assertEquals(0,pool.getNumActive());
        assertEquals(0,pool.getNumIdle());
        assertEquals(0,pool.getNumActive("A"));
        assertEquals(0,pool.getNumIdle("A"));
        assertEquals(0,pool.getNumActive("B"));
        assertEquals(0,pool.getNumIdle("B"));

        Object objA0 = pool.borrowObject("A");
        Object objB0 = pool.borrowObject("B");

        assertEquals(2,pool.getNumActive());
        assertEquals(0,pool.getNumIdle());
        assertEquals(1,pool.getNumActive("A"));
        assertEquals(0,pool.getNumIdle("A"));
        assertEquals(1,pool.getNumActive("B"));
        assertEquals(0,pool.getNumIdle("B"));

        Object objA1 = pool.borrowObject("A");
        Object objB1 = pool.borrowObject("B");

        assertEquals(4,pool.getNumActive());
        assertEquals(0,pool.getNumIdle());
        assertEquals(2,pool.getNumActive("A"));
        assertEquals(0,pool.getNumIdle("A"));
        assertEquals(2,pool.getNumActive("B"));
        assertEquals(0,pool.getNumIdle("B"));

        pool.returnObject("A",objA0);
        pool.returnObject("B",objB0);

        assertEquals(2,pool.getNumActive());
        assertEquals(2,pool.getNumIdle());
        assertEquals(1,pool.getNumActive("A"));
        assertEquals(1,pool.getNumIdle("A"));
        assertEquals(1,pool.getNumActive("B"));
        assertEquals(1,pool.getNumIdle("B"));

        pool.returnObject("A",objA1);
        pool.returnObject("B",objB1);

        assertEquals(0,pool.getNumActive());
        assertEquals(4,pool.getNumIdle());
        assertEquals(0,pool.getNumActive("A"));
        assertEquals(2,pool.getNumIdle("A"));
        assertEquals(0,pool.getNumActive("B"));
        assertEquals(2,pool.getNumIdle("B"));
    }

    public void testMaxIdle() throws Exception {
        pool.setMaxActive(100);
        pool.setMaxIdle(8);
        Object[] active = new Object[100];
        for(int i=0;i<100;i++) {
            active[i] = pool.borrowObject("");
        }
        assertEquals(100,pool.getNumActive(""));
        assertEquals(0,pool.getNumIdle(""));
        for(int i=0;i<100;i++) {
            pool.returnObject("",active[i]);
            assertEquals(99 - i,pool.getNumActive(""));
            assertEquals((i < 8 ? i+1 : 8),pool.getNumIdle(""));
        }
        
        for(int i=0;i<100;i++) {
            active[i] = pool.borrowObject("a");
        }
        assertEquals(100,pool.getNumActive("a"));
        assertEquals(0,pool.getNumIdle("a"));
        for(int i=0;i<100;i++) {
            pool.returnObject("a",active[i]);
            assertEquals(99 - i,pool.getNumActive("a"));
            assertEquals((i < 8 ? i+1 : 8),pool.getNumIdle("a"));
        }
        
        // total number of idle instances is twice maxIdle
        assertEquals(16, pool.getNumIdle());
        // Each pool is at the sup
        assertEquals(8, pool.getNumIdle(""));
        assertEquals(8, pool.getNumIdle("a"));
             
    }

    public void testMaxActive() throws Exception {
        pool.setMaxActive(3);
        pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_FAIL);

        pool.borrowObject("");
        pool.borrowObject("");
        pool.borrowObject("");
        try {
            pool.borrowObject("");
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    public void testMaxActiveZero() throws Exception {
        pool.setMaxActive(0);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);

        try {
            pool.borrowObject("a");
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    public void testMaxTotal() throws Exception {
        pool.setMaxActive(2);
        pool.setMaxTotal(3);
        pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_FAIL);

        Object o1 = pool.borrowObject("a");
        assertNotNull(o1);
        Object o2 = pool.borrowObject("a");
        assertNotNull(o2);
        Object o3 = pool.borrowObject("b");
        assertNotNull(o3);
        try {
            pool.borrowObject("c");
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }

        assertEquals(0, pool.getNumIdle());

        pool.returnObject("b", o3);
        assertEquals(1, pool.getNumIdle());
        assertEquals(1, pool.getNumIdle("b"));

        Object o4 = pool.borrowObject("b");
        assertNotNull(o4);
        assertEquals(0, pool.getNumIdle());
        assertEquals(0, pool.getNumIdle("b"));
        
        pool.setMaxTotal(4);
        Object o5 = pool.borrowObject("b");
        assertNotNull(o5);
        
        assertEquals(2, pool.getNumActive("a"));
        assertEquals(2, pool.getNumActive("b"));
        assertEquals(pool.getMaxTotal(),
                pool.getNumActive("b") + pool.getNumActive("b"));
        assertEquals(pool.getNumActive(),
                pool.getMaxTotal());
    }

    public void testMaxTotalZero() throws Exception {
        pool.setMaxTotal(0);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);

        try {
            pool.borrowObject("a");
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    public void testMaxTotalLRU() throws Exception {
        pool.setMaxActive(2);
        pool.setMaxTotal(3);
//        pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_GROW);

        Object o1 = pool.borrowObject("a");
        assertNotNull(o1);
        pool.returnObject("a", o1);
        Thread.sleep(25);

        Object o2 = pool.borrowObject("b");
        assertNotNull(o2);
        pool.returnObject("b", o2);
        Thread.sleep(25);

        Object o3 = pool.borrowObject("c");
        assertNotNull(o3);
        pool.returnObject("c", o3);
        Thread.sleep(25);

        Object o4 = pool.borrowObject("a");
        assertNotNull(o4);
        pool.returnObject("a", o4);
        Thread.sleep(25);

        assertSame(o1, o4);

        // this should cause b to be bumped out of the pool
        Object o5 = pool.borrowObject("d");
        assertNotNull(o5);
        pool.returnObject("d", o5);
        Thread.sleep(25);

        // now re-request b, we should get a different object because it should
        // have been expelled from pool (was oldest because a was requested after b)
        Object o6 = pool.borrowObject("b");
        assertNotNull(o6);
        pool.returnObject("b", o6);

        assertNotSame(o1, o6);

        // second a is still in there
        Object o7 = pool.borrowObject("a");
        assertNotNull(o7);
        pool.returnObject("a", o7);

        assertSame(o4, o7);
    }

    public void testSettersAndGetters() throws Exception {
        GenericKeyedObjectPool pool = new GenericKeyedObjectPool();
        {
            pool.setFactory(new SimpleFactory());
        }
        {
            pool.setMaxActive(123);
            assertEquals(123,pool.getMaxActive());
        }
        {
            pool.setMaxIdle(12);
            assertEquals(12,pool.getMaxIdle());
        }
        {
            pool.setMaxWait(1234L);
            assertEquals(1234L,pool.getMaxWait());
        }
        {
            pool.setMinEvictableIdleTimeMillis(12345L);
            assertEquals(12345L,pool.getMinEvictableIdleTimeMillis());
        }
        {
            pool.setNumTestsPerEvictionRun(11);
            assertEquals(11,pool.getNumTestsPerEvictionRun());
        }
        {
            pool.setTestOnBorrow(true);
            assertTrue(pool.getTestOnBorrow());
            pool.setTestOnBorrow(false);
            assertTrue(!pool.getTestOnBorrow());
        }
        {
            pool.setTestOnReturn(true);
            assertTrue(pool.getTestOnReturn());
            pool.setTestOnReturn(false);
            assertTrue(!pool.getTestOnReturn());
        }
        {
            pool.setTestWhileIdle(true);
            assertTrue(pool.getTestWhileIdle());
            pool.setTestWhileIdle(false);
            assertTrue(!pool.getTestWhileIdle());
        }
        {
            pool.setTimeBetweenEvictionRunsMillis(11235L);
            assertEquals(11235L,pool.getTimeBetweenEvictionRunsMillis());
        }
        {
            pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_BLOCK);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_BLOCK,pool.getWhenExhaustedAction());
            pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_FAIL);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_FAIL,pool.getWhenExhaustedAction());
            pool.setWhenExhaustedAction(GenericKeyedObjectPool.WHEN_EXHAUSTED_GROW);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_GROW,pool.getWhenExhaustedAction());
        }
    }

    public void testEviction() throws Exception {
        pool.setMaxIdle(500);
        pool.setMaxActive(500);
        pool.setNumTestsPerEvictionRun(100);
        pool.setMinEvictableIdleTimeMillis(250L);
        pool.setTimeBetweenEvictionRunsMillis(500L);

        Object[] active = new Object[500];
        for(int i=0;i<500;i++) {
            active[i] = pool.borrowObject("");
        }
        for(int i=0;i<500;i++) {
            pool.returnObject("",active[i]);
        }

        try { Thread.sleep(1000L); } catch(Exception e) { }
        assertTrue("Should be less than 500 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 500);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 400 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 400);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 300 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 300);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 200 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 200);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 100 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 100);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertEquals("Should be zero idle, found " + pool.getNumIdle(""),0,pool.getNumIdle(""));

        for(int i=0;i<500;i++) {
            active[i] = pool.borrowObject("");
        }
        for(int i=0;i<500;i++) {
            pool.returnObject("",active[i]);
        }

        try { Thread.sleep(1000L); } catch(Exception e) { }
        assertTrue("Should be less than 500 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 500);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 400 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 400);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 300 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 300);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 200 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 200);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 100 idle, found " + pool.getNumIdle(""),pool.getNumIdle("") < 100);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertEquals("Should be zero idle, found " + pool.getNumIdle(""),0,pool.getNumIdle(""));
    }

    public void testEviction2() throws Exception {
        pool.setMaxIdle(500);
        pool.setMaxActive(500);
        pool.setNumTestsPerEvictionRun(100);
        pool.setMinEvictableIdleTimeMillis(500L);
        pool.setTimeBetweenEvictionRunsMillis(500L);

        Object[] active = new Object[500];
        Object[] active2 = new Object[500];
        for(int i=0;i<500;i++) {
            active[i] = pool.borrowObject("");
            active2[i] = pool.borrowObject("2");
        }
        for(int i=0;i<500;i++) {
            pool.returnObject("",active[i]);
            pool.returnObject("2",active2[i]);
        }

        try { Thread.sleep(1000L); } catch(Exception e) { }
        assertTrue("Should be less than 1000 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 1000);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 900 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 900);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 800 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 800);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 700 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 700);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 600 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 600);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 500 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 500);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 400 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 400);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 300 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 300);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 200 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 200);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertTrue("Should be less than 100 idle, found " + pool.getNumIdle(),pool.getNumIdle() < 100);
        try { Thread.sleep(600L); } catch(Exception e) { }
        assertEquals("Should be zero idle, found " + pool.getNumIdle(),0,pool.getNumIdle());
    }

    /**
     * Kicks off <numThreads> test threads, each of which will go through
     * <iterations> borrow-return cycles with random delay times <= delay
     * in between.
     */
    public void runTestThreads(int numThreads, int iterations, int delay) {
        TestThread[] threads = new TestThread[numThreads];
        for(int i=0;i<numThreads;i++) {
            threads[i] = new TestThread(pool,iterations,delay);
            Thread t = new Thread(threads[i]);
            t.start();
        }
        for(int i=0;i<numThreads;i++) {
            while(!(threads[i]).complete()) {
                try {
                    Thread.sleep(500L);
                } catch(Exception e) {
                    // ignored
                }
            }
            if(threads[i].failed()) {
                fail();
            }
        }
    }
    
    public void testThreaded1() throws Exception {
        pool.setMaxActive(15);
        pool.setMaxIdle(15);
        pool.setMaxWait(1000L);
        runTestThreads(20, 100, 50);
    }
    
    /**
     * Verifies that maxTotal is not exceeded when factory destroyObject
     * has high latency, testOnReturn is set and there is high incidence of
     * validation failures. 
     */
    public void testMaxTotalInvariant() throws Exception {
        int maxTotal = 15;
        SimpleFactory factory = new SimpleFactory();
        factory.setEvenValid(false);     // Every other validation fails
        factory.setDestroyLatency(100);  // Destroy takes 100 ms
        factory.setMaxActive(maxTotal);  // (makes - destroys) bound
        factory.setValidationEnabled(true);
        pool = new GenericKeyedObjectPool(factory);
        pool.setMaxTotal(maxTotal);
        pool.setMaxIdle(-1);
        pool.setTestOnReturn(true);
        pool.setMaxWait(10000L);
        runTestThreads(5, 10, 50);
    }

    public void testMinIdle() throws Exception {
        pool.setMaxIdle(500);
        pool.setMinIdle(5);
        pool.setMaxActive(10);
        pool.setNumTestsPerEvictionRun(0);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(100L);
        pool.setTestWhileIdle(true);


        //Generate a random key
        String key = "A";

        pool.preparePool(key, true);

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        Object[] active = new Object[5];
        active[0] = pool.borrowObject(key);

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=1 ; i<5 ; i++) {
            active[i] = pool.borrowObject(key);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            pool.returnObject(key, active[i]);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 10 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 10);
    }

    public void testMinIdleMaxActive() throws Exception {
        pool.setMaxIdle(500);
        pool.setMinIdle(5);
        pool.setMaxActive(10);
        pool.setNumTestsPerEvictionRun(0);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(100L);
        pool.setTestWhileIdle(true);

        String key = "A";

        pool.preparePool(key, true);
        assertTrue("Should be 5 idle, found " + 
                pool.getNumIdle(),pool.getNumIdle() == 5);

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        Object[] active = new Object[10];

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            active[i] = pool.borrowObject(key);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            pool.returnObject(key, active[i]);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 10 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 10);

        for(int i=0 ; i<10 ; i++) {
            active[i] = pool.borrowObject(key);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 0 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 0);

        for(int i=0 ; i<10 ; i++) {
            pool.returnObject(key, active[i]);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 10 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 10);
    }

    public void testMinIdleNoPopulateImmediately() throws Exception {
        pool.setMaxIdle(500);
        pool.setMinIdle(5);
        pool.setMaxActive(10);
        pool.setNumTestsPerEvictionRun(0);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(1000L);
        pool.setTestWhileIdle(true);


        //Generate a random key
        String key = "A";

        pool.preparePool(key, false);

        assertTrue("Should be 0 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 0);

        try { Thread.sleep(1500L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);
    }

    public void testMinIdleNoPreparePool() throws Exception {
        pool.setMaxIdle(500);
        pool.setMinIdle(5);
        pool.setMaxActive(10);
        pool.setNumTestsPerEvictionRun(0);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(100L);
        pool.setTestWhileIdle(true);


        //Generate a random key
        String key = "A";

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 0 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 0);

        Object active = pool.borrowObject(key);
        assertNotNull(active);

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);
    }

    public void testFIFO() throws Exception {
        pool.setLifo(false);
        final Object key = "key";
        pool.addObject(key); // "key0"
        pool.addObject(key); // "key1"
        pool.addObject(key); // "key2"
        assertEquals("Oldest", "key0", pool.borrowObject(key));
        assertEquals("Middle", "key1", pool.borrowObject(key));
        assertEquals("Youngest", "key2", pool.borrowObject(key));
        assertEquals("new-3", "key3", pool.borrowObject(key));
        pool.returnObject(key, "r");
        assertEquals("returned", "r", pool.borrowObject(key));
        assertEquals("new-4", "key4", pool.borrowObject(key));
    }
    
    public void testLIFO() throws Exception {
        pool.setLifo(true);
        final Object key = "key";
        pool.addObject(key); // "key0"
        pool.addObject(key); // "key1"
        pool.addObject(key); // "key2"
        assertEquals("Youngest", "key2", pool.borrowObject(key));
        assertEquals("Middle", "key1", pool.borrowObject(key));
        assertEquals("Oldest", "key0", pool.borrowObject(key));
        assertEquals("new-3", "key3", pool.borrowObject(key));
        pool.returnObject(key, "r");
        assertEquals("returned", "r", pool.borrowObject(key));
        assertEquals("new-4", "key4", pool.borrowObject(key));
    }
    
    /**
     * Test to make sure evictor visits least recently used objects first,
     * regardless of FIFO/LIFO 
     * 
     * JIRA: POOL-86
     */ 
    public void testEvictionOrder() throws Exception {
        checkEvictionOrder(false);
        checkEvictionOrder(true);
    }
    
    private void checkEvictionOrder(boolean lifo) throws Exception {
        SimpleFactory factory = new SimpleFactory();
        GenericKeyedObjectPool pool = new GenericKeyedObjectPool(factory);
        pool.setNumTestsPerEvictionRun(2);
        pool.setMinEvictableIdleTimeMillis(100);
        pool.setLifo(lifo);
        
        for (int i = 0; i < 3; i ++) {
            Integer key = new Integer(i);
            for (int j = 0; j < 5; j++) {
                pool.addObject(key);
            }
        }
        
        // Make all evictable
        Thread.sleep(200);
        
        /* 
         * Initial state (Key, Object) pairs in order of age:
         * 
         * (0,0), (0,1), (0,2), (0,3), (0,4)
         * (1,5), (1,6), (1,7), (1,8), (1,9)
         * (2,10), (2,11), (2,12), (2,13), (2,14)
         */
        
        pool.evict(); // Kill (0,0),(0,1)
        assertEquals(3, pool.getNumIdle(zero));
        Object obj = pool.borrowObject(zero);
        assertTrue(lifo ? obj.equals("04") : obj.equals("02"));
        assertEquals(2, pool.getNumIdle(zero));
        obj = pool.borrowObject(zero);
        assertTrue(obj.equals("03"));
        assertEquals(1, pool.getNumIdle(zero));
        
        pool.evict(); // Kill remaining 0 survivor and (1,5)
        assertEquals(0, pool.getNumIdle(zero));
        assertEquals(4, pool.getNumIdle(one));
        obj = pool.borrowObject(one);
        assertTrue(lifo ? obj.equals("19") : obj.equals("16"));
        assertEquals(3, pool.getNumIdle(one));
        obj = pool.borrowObject(one);
        assertTrue(lifo ? obj.equals("18") : obj.equals("17"));
        assertEquals(2, pool.getNumIdle(one));
        
        pool.evict(); // Kill remaining 1 survivors
        assertEquals(0, pool.getNumIdle(one));
        pool.evict(); // Kill (2,10), (2,11)
        assertEquals(3, pool.getNumIdle(two));
        obj = pool.borrowObject(two);
        assertTrue(lifo ? obj.equals("214") : obj.equals("212"));
        assertEquals(2, pool.getNumIdle(two));
        pool.evict(); // All dead now
        assertEquals(0, pool.getNumIdle(two));  
        
        pool.evict(); // Should do nothing - make sure no exception
        pool.evict();
        
        // Reload
        pool.setMinEvictableIdleTimeMillis(500);
        factory.counter = 0; // Reset counter
        for (int i = 0; i < 3; i ++) {
            Integer key = new Integer(i);
            for (int j = 0; j < 5; j++) {
                pool.addObject(key);
            }
            Thread.sleep(200);
        }
        
        // 0's are evictable, others not 
        pool.evict(); // Kill (0,0),(0,1)
        assertEquals(3, pool.getNumIdle(zero));
        pool.evict(); // Kill (0,2),(0,3)
        assertEquals(1, pool.getNumIdle(zero));
        pool.evict(); // Kill (0,4), leave (1,5)
        assertEquals(0, pool.getNumIdle(zero));
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        pool.evict(); // (1,6), (1,7)
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        pool.evict(); // (1,8), (1,9)
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        pool.evict(); // (2,10), (2,11)
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        pool.evict(); // (2,12), (2,13)
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        pool.evict(); // (2,14), (1,5)
        assertEquals(5, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        Thread.sleep(200); // Ones now timed out
        pool.evict(); // kill (1,6), (1,7) - (1,5) missed
        assertEquals(3, pool.getNumIdle(one));
        assertEquals(5, pool.getNumIdle(two));
        obj = pool.borrowObject(one);
        assertTrue(lifo ? obj.equals("19") : obj.equals("15"));  
    }
    
    
    /**
     * Verifies that the evictor visits objects in expected order
     * and frequency. 
     */
    public void testEvictorVisiting() throws Exception {
        checkEvictorVisiting(true);
        checkEvictorVisiting(false);  
    }
    
    private void checkEvictorVisiting(boolean lifo) throws Exception {
        VisitTrackerFactory factory = new VisitTrackerFactory();
        GenericKeyedObjectPool pool = new GenericKeyedObjectPool(factory);
        pool.setNumTestsPerEvictionRun(2);
        pool.setMinEvictableIdleTimeMillis(-1);
        pool.setTestWhileIdle(true);
        pool.setLifo(lifo);
        pool.setTestOnReturn(false);
        pool.setTestOnBorrow(false);
        for (int i = 0; i < 3; i ++) {
            factory.resetId();
            Integer key = new Integer(i);
            for (int j = 0; j < 8; j++) {
                pool.addObject(key);
            }
        }
        pool.evict(); // Visit oldest 2 - 00 and 01
        Object obj = pool.borrowObject(zero);
        pool.returnObject(zero, obj);
        obj = pool.borrowObject(zero);
        pool.returnObject(zero, obj);
        //  borrow, return, borrow, return 
        //  FIFO will move 0 and 1 to end - 2,3,4,5,6,7,0,1
        //  LIFO, 7 out, then in, then out, then in - 7,6,5,4,3,2,1,0
        pool.evict();  // Should visit 02 and 03 in either case
        for (int i = 0; i < 8; i++) {
            VisitTracker tracker = (VisitTracker) pool.borrowObject(zero);    
            if (tracker.getId() >= 4) {
                assertEquals("Unexpected instance visited " + tracker.getId(),
                        0, tracker.getValidateCount());
            } else {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        1, tracker.getValidateCount());
            }
        } 
        // 0's are all out
        
        pool.setNumTestsPerEvictionRun(3);
        
        pool.evict(); // 10, 11, 12
        pool.evict(); // 13, 14, 15
        
        obj = pool.borrowObject(one);
        pool.returnObject(one, obj);
        obj = pool.borrowObject(one);
        pool.returnObject(one, obj);
        obj = pool.borrowObject(one);
        pool.returnObject(one, obj);
        // borrow, return, borrow, return 
        //  FIFO 3,4,5,^,6,7,0,1,2
        //  LIFO 7,6,^,5,4,3,2,1,0
        // In either case, pointer should be at 6
        pool.evict();
        // LIFO - 16, 17, 20
        // FIFO - 16, 17, 10
        pool.evict();
        // LIFO - 21, 22, 23
        // FIFO - 11, 12, 20
        pool.evict();
        // LIFO - 24, 25, 26
        // FIFO - 21, 22, 23
        pool.evict();
        // LIFO - 27, skip, 10
        // FIFO - 24, 25, 26
        for (int i = 0; i < 8; i++) {
            VisitTracker tracker = (VisitTracker) pool.borrowObject(one);    
            if ((lifo && tracker.getId() > 0) || 
                    (!lifo && tracker.getId() > 2)) {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        1, tracker.getValidateCount());
            } else {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        2, tracker.getValidateCount());
            }
        } 
        
        // Randomly generate some pools with random numTests
        // and make sure evictor cycles through elements appropriately
        int[] smallPrimes = {2, 3, 5, 7};
        Random random = new Random();
        random.setSeed(System.currentTimeMillis());
        pool.setMaxIdle(-1);
        for (int i = 0; i < 4; i++) {
            pool.setNumTestsPerEvictionRun(smallPrimes[i]);
            for (int j = 0; j < 5; j++) {
                pool.clear();
                int zeroLength = 10 + random.nextInt(20);
                for (int k = 0; k < zeroLength; k++) {
                    pool.addObject(zero);
                }
                int oneLength = 10 + random.nextInt(20);
                for (int k = 0; k < oneLength; k++) {
                    pool.addObject(one);
                }
                int twoLength = 10 + random.nextInt(20);
                for (int k = 0; k < twoLength; k++) {
                    pool.addObject(two);
                }
                
                // Choose a random number of evictor runs
                int runs = 10 + random.nextInt(50);
                for (int k = 0; k < runs; k++) {
                    pool.evict();
                }
                
                // Total instances in pool
                int totalInstances = zeroLength + oneLength + twoLength;
                
                // Number of times evictor should have cycled through pools
                int cycleCount = (runs * pool.getNumTestsPerEvictionRun())
                    / totalInstances;
                
                // Look at elements and make sure they are visited cycleCount
                // or cycleCount + 1 times
                VisitTracker tracker = null;
                int visitCount = 0;
                for (int k = 0; k < zeroLength; k++) {
                    tracker = (VisitTracker) pool.borrowObject(zero); 
                    visitCount = tracker.getValidateCount();                  
                    assertTrue(visitCount >= cycleCount && 
                            visitCount <= cycleCount + 1);
                }
                for (int k = 0; k < oneLength; k++) {
                    tracker = (VisitTracker) pool.borrowObject(one); 
                    visitCount = tracker.getValidateCount();
                    assertTrue(visitCount >= cycleCount && 
                            visitCount <= cycleCount + 1);
                }
                for (int k = 0; k < twoLength; k++) {
                    tracker = (VisitTracker) pool.borrowObject(two); 
                    visitCount = tracker.getValidateCount();
                    assertTrue(visitCount >= cycleCount && 
                            visitCount <= cycleCount + 1);
                } 
            }
        }
    }
    
    public void testConstructors() {
        
        // Make constructor arguments all different from defaults
        int maxActive = 1;
        int maxIdle = 2;
        long maxWait = 3;
        int minIdle = 4;
        int maxTotal = 5;
        long minEvictableIdleTimeMillis = 6;
        int numTestsPerEvictionRun = 7;
        boolean testOnBorrow = true;
        boolean testOnReturn = true;
        boolean testWhileIdle = true;
        long timeBetweenEvictionRunsMillis = 8;
        byte whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
        boolean lifo = false;
        
        GenericKeyedObjectPool pool = new GenericKeyedObjectPool();
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_ACTIVE, pool.getMaxActive());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_IDLE, pool.getMaxIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_WAIT, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_BORROW,
                pool.getTestOnBorrow());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_RETURN,
                pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_WHEN_EXHAUSTED_ACTION,
                pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        GenericKeyedObjectPool.Config config = new GenericKeyedObjectPool.Config();
        config.lifo = lifo;
        config.maxActive = maxActive;
        config.maxIdle = maxIdle;
        config.minIdle = minIdle;
        config.maxTotal = maxTotal;
        config.maxWait = maxWait;
        config.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
        config.numTestsPerEvictionRun = numTestsPerEvictionRun;
        config.testOnBorrow = testOnBorrow;
        config.testOnReturn = testOnReturn;
        config.testWhileIdle = testWhileIdle;
        config.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        config.whenExhaustedAction = whenExhaustedAction;
        pool = new GenericKeyedObjectPool(null, config);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(minIdle, pool.getMinIdle());
        assertEquals(maxTotal, pool.getMaxTotal());
        assertEquals(minEvictableIdleTimeMillis,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(numTestsPerEvictionRun, pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow,pool.getTestOnBorrow());
        assertEquals(testOnReturn,pool.getTestOnReturn());
        assertEquals(testWhileIdle,pool.getTestWhileIdle());
        assertEquals(timeBetweenEvictionRunsMillis,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(lifo, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_IDLE, pool.getMaxIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_WAIT, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_BORROW,
                pool.getTestOnBorrow());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_RETURN,
                pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_WHEN_EXHAUSTED_ACTION,
                pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction, maxWait);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_IDLE, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_BORROW,
                pool.getTestOnBorrow());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_RETURN,
                pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                   maxWait, testOnBorrow, testOnReturn);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_IDLE, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow,pool.getTestOnBorrow());
        assertEquals(testOnReturn,pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_BORROW,
                pool.getTestOnBorrow());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_ON_RETURN,
                pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());

        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle, testOnBorrow, testOnReturn);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(GenericKeyedObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow, pool.getTestOnBorrow());
        assertEquals(testOnReturn, pool.getTestOnReturn());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TEST_WHILE_IDLE,
                pool.getTestWhileIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());

        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle, testOnBorrow, testOnReturn,
                timeBetweenEvictionRunsMillis, numTestsPerEvictionRun,
                minEvictableIdleTimeMillis, testWhileIdle);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MAX_TOTAL, pool.getMaxTotal());
        assertEquals(minEvictableIdleTimeMillis,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(numTestsPerEvictionRun,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow, pool.getTestOnBorrow());
        assertEquals(testOnReturn, pool.getTestOnReturn());
        assertEquals(testWhileIdle,
                pool.getTestWhileIdle());
        assertEquals(timeBetweenEvictionRunsMillis,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle, maxTotal, testOnBorrow, testOnReturn,
                timeBetweenEvictionRunsMillis, numTestsPerEvictionRun,
                minEvictableIdleTimeMillis, testWhileIdle);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(GenericKeyedObjectPool.DEFAULT_MIN_IDLE, pool.getMinIdle());
        assertEquals(maxTotal, pool.getMaxTotal());
        assertEquals(minEvictableIdleTimeMillis,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(numTestsPerEvictionRun,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow, pool.getTestOnBorrow());
        assertEquals(testOnReturn, pool.getTestOnReturn());
        assertEquals(testWhileIdle,
                pool.getTestWhileIdle());
        assertEquals(timeBetweenEvictionRunsMillis,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle, maxTotal, minIdle, testOnBorrow, testOnReturn,
                timeBetweenEvictionRunsMillis, numTestsPerEvictionRun,
                minEvictableIdleTimeMillis, testWhileIdle);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(minIdle, pool.getMinIdle());
        assertEquals(maxTotal, pool.getMaxTotal());
        assertEquals(minEvictableIdleTimeMillis,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(numTestsPerEvictionRun,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow, pool.getTestOnBorrow());
        assertEquals(testOnReturn, pool.getTestOnReturn());
        assertEquals(testWhileIdle,
                pool.getTestWhileIdle());
        assertEquals(timeBetweenEvictionRunsMillis,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(GenericKeyedObjectPool.DEFAULT_LIFO, pool.getLifo());
        
        pool = new GenericKeyedObjectPool(null, maxActive, whenExhaustedAction,
                maxWait, maxIdle, maxTotal, minIdle, testOnBorrow, testOnReturn,
                timeBetweenEvictionRunsMillis, numTestsPerEvictionRun,
                minEvictableIdleTimeMillis, testWhileIdle, lifo);
        assertEquals(maxActive, pool.getMaxActive());
        assertEquals(maxIdle, pool.getMaxIdle());
        assertEquals(maxWait, pool.getMaxWait());
        assertEquals(minIdle, pool.getMinIdle());
        assertEquals(maxTotal, pool.getMaxTotal());
        assertEquals(minEvictableIdleTimeMillis,
                pool.getMinEvictableIdleTimeMillis());
        assertEquals(numTestsPerEvictionRun,
                pool.getNumTestsPerEvictionRun());
        assertEquals(testOnBorrow, pool.getTestOnBorrow());
        assertEquals(testOnReturn, pool.getTestOnReturn());
        assertEquals(testWhileIdle,
                pool.getTestWhileIdle());
        assertEquals(timeBetweenEvictionRunsMillis,
                pool.getTimeBetweenEvictionRunsMillis());
        assertEquals(whenExhaustedAction,pool.getWhenExhaustedAction());
        assertEquals(lifo, pool.getLifo());  
    }

    class TestThread implements Runnable {
        java.util.Random _random = new java.util.Random();
        KeyedObjectPool _pool = null;
        boolean _complete = false;
        boolean _failed = false;
        int _iter = 100;
        int _delay = 50;

        public TestThread(KeyedObjectPool pool) {
            _pool = pool;
        }

        public TestThread(KeyedObjectPool pool, int iter) {
            _pool = pool;
            _iter = iter;
        }

        public TestThread(KeyedObjectPool pool, int iter, int delay) {
            _pool = pool;
            _iter = iter;
            _delay = delay;
        }

        public boolean complete() {
            return _complete;
        }

        public boolean failed() {
            return _failed;
        }

        public void run() {
            for(int i=0;i<_iter;i++) {
                String key = String.valueOf(_random.nextInt(3));
                try {
                    Thread.sleep((long)_random.nextInt(_delay));
                } catch(Exception e) {
                    // ignored
                }
                Object obj = null;
                try {
                    obj = _pool.borrowObject(key);
                } catch(Exception e) {
                    e.printStackTrace();
                    _failed = true;
                    _complete = true;
                    break;
                }

                try {
                    Thread.sleep((long)_random.nextInt(_delay));
                } catch(Exception e) {
                    // ignored
                }
                try {
                    _pool.returnObject(key,obj);
                } catch(Exception e) {
                    e.printStackTrace();
                    _failed = true;
                    _complete = true;
                    break;
                }
            }
            _complete = true;
        }
    }

    static class SimpleFactory implements KeyedPoolableObjectFactory {
        public SimpleFactory() {
            this(true);
        }
        public SimpleFactory(boolean valid) {
            this.valid = valid;
        }
        public Object makeObject(Object key) {
            synchronized(this) {
                activeCount++;
                if (activeCount > maxActive) {
                    throw new IllegalStateException(
                        "Too many active instances: " + activeCount);
                }
            }
            return String.valueOf(key) + String.valueOf(counter++);
        }
        public void destroyObject(Object key, Object obj) {
            doWait(destroyLatency);
            synchronized(this) {
                activeCount--;
            }
        }
        public boolean validateObject(Object key, Object obj) {
            if (enableValidation) { 
                return validateCounter++%2 == 0 ? evenValid : oddValid; 
            } else {
                return valid;
            }
        }
        public void activateObject(Object key, Object obj) { }
        public void passivateObject(Object key, Object obj) { }
        
        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }
        public void setDestroyLatency(long destroyLatency) {
            this.destroyLatency = destroyLatency;
        }
        public void setValidationEnabled(boolean b) {
            enableValidation = b;
        }
        void setEvenValid(boolean valid) {
            evenValid = valid;
        }
        
        int counter = 0;
        boolean valid;
        
        int activeCount = 0;
        int validateCounter = 0;
        boolean evenValid = true;
        boolean oddValid = true;
        boolean enableValidation = false;
        long destroyLatency = 0;
        int maxActive = Integer.MAX_VALUE;
        
        private void doWait(long latency) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException ex) {
                // ignore
            }
        }
    }

    protected boolean isLifo() {
        return true;
    }

    protected boolean isFifo() {
        return false;
    }

}


