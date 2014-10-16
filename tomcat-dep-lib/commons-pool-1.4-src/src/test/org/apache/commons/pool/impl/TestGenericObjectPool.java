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
import org.apache.commons.pool.BasePoolableObjectFactory;
import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.commons.pool.PoolUtils;
import org.apache.commons.pool.TestBaseObjectPool;
import org.apache.commons.pool.VisitTracker;
import org.apache.commons.pool.VisitTrackerFactory;

import java.util.NoSuchElementException;
import java.util.Random;

/**
 * @author Rodney Waldhoff
 * @author Dirk Verbeeck
 * @author Sandy McArthur
 * @version $Revision: 609415 $ $Date: 2008-01-06 14:45:32 -0700 (Sun, 06 Jan 2008) $
 */
public class TestGenericObjectPool extends TestBaseObjectPool {
    public TestGenericObjectPool(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestGenericObjectPool.class);
    }

    protected ObjectPool makeEmptyPool(int mincap) {
       GenericObjectPool pool = new GenericObjectPool(new SimpleFactory());
       pool.setMaxActive(mincap);
       pool.setMaxIdle(mincap);
       return pool;
    }

    protected ObjectPool makeEmptyPool(final PoolableObjectFactory factory) {
        return new GenericObjectPool(factory);
    }

    protected Object getNthObject(int n) {
        return String.valueOf(n);
    }

    public void setUp() throws Exception {
        super.setUp();
        pool = new GenericObjectPool(new SimpleFactory());
    }

    public void tearDown() throws Exception {
        super.tearDown();
        pool.close();
        pool = null;
    }

    public void testWhenExhaustedGrow() throws Exception {
        pool.setMaxActive(1);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_GROW);
        Object obj1 = pool.borrowObject();
        assertNotNull(obj1);
        Object obj2 = pool.borrowObject();
        assertNotNull(obj2);
        pool.returnObject(obj2);
        pool.returnObject(obj1);
        pool.close();
    }

    public void testWhenExhaustedFail() throws Exception {
        pool.setMaxActive(1);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);
        Object obj1 = pool.borrowObject();
        assertNotNull(obj1);
        try {
            pool.borrowObject();
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
        pool.returnObject(obj1);
        pool.close();
    }

    public void testWhenExhaustedBlock() throws Exception {
        pool.setMaxActive(1);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);
        pool.setMaxWait(10L);
        Object obj1 = pool.borrowObject();
        assertNotNull(obj1);
        try {
            pool.borrowObject();
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
        pool.returnObject(obj1);
        pool.close();
    }

    public void testEvictWhileEmpty() throws Exception {
        pool.evict();
        pool.evict();
        pool.close();
    }
    
    /**
     * Tests addObject contention between ensureMinIdle triggered by
     * the Evictor with minIdle > 0 and borrowObject. 
     */
    public void testEvictAddObjects() throws Exception {
        SimpleFactory factory = new SimpleFactory();
        factory.setMakeLatency(300);
        factory.setMaxActive(2);
        GenericObjectPool pool = new GenericObjectPool(factory);
        pool.setMaxActive(2);
        pool.setMinIdle(1);
        Object obj = pool.borrowObject(); // numActive = 1, numIdle = 0
        // Create a test thread that will run once and try a borrow after
        // 150ms fixed delay
        TestThread borrower = new TestThread(pool, 1, 150, false);
        Thread borrowerThread = new Thread(borrower);
        // Set evictor to run in 100 ms - will create idle instance
        pool.setTimeBetweenEvictionRunsMillis(100);
        borrowerThread.start();  // Off to the races
        borrowerThread.join();
        assertTrue(!borrower.failed());
        pool.close();
    }

    public void testEvictLIFO() throws Exception {
        checkEvict(true);   
    }
    
    public void testEvictFIFO() throws Exception {
        checkEvict(false);
    }
    
    public void checkEvict(boolean lifo) throws Exception {
        // yea this is hairy but it tests all the code paths in GOP.evict()
        final SimpleFactory factory = new SimpleFactory();
        final GenericObjectPool pool = new GenericObjectPool(factory);
        pool.setSoftMinEvictableIdleTimeMillis(10);
        pool.setMinIdle(2);
        pool.setTestWhileIdle(true);
        pool.setLifo(lifo);
        PoolUtils.prefill(pool, 5);
        pool.evict();
        factory.setEvenValid(false);
        factory.setOddValid(false);
        factory.setThrowExceptionOnActivate(true);
        pool.evict();
        PoolUtils.prefill(pool, 5);
        factory.setThrowExceptionOnActivate(false);
        factory.setThrowExceptionOnPassivate(true);
        pool.evict();
        factory.setThrowExceptionOnPassivate(false);
        factory.setEvenValid(true);
        factory.setOddValid(true);
        Thread.sleep(125);
        pool.evict();
        assertEquals(2, pool.getNumIdle());
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
        GenericObjectPool pool = new GenericObjectPool(factory);
        pool.setNumTestsPerEvictionRun(2);
        pool.setMinEvictableIdleTimeMillis(100);
        pool.setLifo(lifo);
        for (int i = 0; i < 5; i++) {
            pool.addObject();
            Thread.sleep(100);
        }
        // Order, oldest to youngest, is "0", "1", ...,"4"
        pool.evict(); // Should evict "0" and "1"
        Object obj = pool.borrowObject();
        assertTrue("oldest not evicted", !obj.equals("0"));
        assertTrue("second oldest not evicted", !obj.equals("1"));
        // 2 should be next out for FIFO, 4 for LIFO
        assertEquals("Wrong instance returned", lifo ? "4" : "2" , obj); 
        
        // Two eviction runs in sequence
        factory = new SimpleFactory();
        pool = new GenericObjectPool(factory);
        pool.setNumTestsPerEvictionRun(2);
        pool.setMinEvictableIdleTimeMillis(100);
        pool.setLifo(lifo);
        for (int i = 0; i < 5; i++) {
            pool.addObject();
            Thread.sleep(100);
        }
        pool.evict(); // Should evict "0" and "1"
        pool.evict(); // Should evict "2" and "3"
        obj = pool.borrowObject();
        assertEquals("Wrong instance remaining in pool", "4", obj);     
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
        GenericObjectPool pool = new GenericObjectPool(factory);
        pool.setNumTestsPerEvictionRun(2);
        pool.setMinEvictableIdleTimeMillis(-1);
        pool.setTestWhileIdle(true);
        pool.setLifo(lifo);
        pool.setTestOnReturn(false);
        pool.setTestOnBorrow(false);
        for (int i = 0; i < 8; i++) {
            pool.addObject();
        }
        pool.evict(); // Visit oldest 2 - 0 and 1
        Object obj = pool.borrowObject();
        pool.returnObject(obj);
        obj = pool.borrowObject();
        pool.returnObject(obj);
        //  borrow, return, borrow, return 
        //  FIFO will move 0 and 1 to end
        //  LIFO, 7 out, then in, then out, then in
        pool.evict();  // Should visit 2 and 3 in either case
        for (int i = 0; i < 8; i++) {
            VisitTracker tracker = (VisitTracker) pool.borrowObject();    
            if (tracker.getId() >= 4) {
                assertEquals("Unexpected instance visited " + tracker.getId(),
                        0, tracker.getValidateCount());
            } else {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        1, tracker.getValidateCount());
            }
        } 

        factory = new VisitTrackerFactory();
        pool = new GenericObjectPool(factory);
        pool.setNumTestsPerEvictionRun(3);
        pool.setMinEvictableIdleTimeMillis(-1);
        pool.setTestWhileIdle(true);
        pool.setLifo(lifo);
        pool.setTestOnReturn(false);
        pool.setTestOnBorrow(false);
        for (int i = 0; i < 8; i++) {
            pool.addObject();
        }
        pool.evict(); // 0, 1, 2
        pool.evict(); // 3, 4, 5
        obj = pool.borrowObject();
        pool.returnObject(obj);
        obj = pool.borrowObject();
        pool.returnObject(obj);
        obj = pool.borrowObject();
        pool.returnObject(obj);
        // borrow, return, borrow, return 
        //  FIFO 3,4,5,6,7,0,1,2
        //  LIFO 7,6,5,4,3,2,1,0
        // In either case, pointer should be at 6
        pool.evict();
        // Should hit 6,7,0 - 0 for second time
        for (int i = 0; i < 8; i++) {
            VisitTracker tracker = (VisitTracker) pool.borrowObject();    
            if (tracker.getId() != 0) {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        1, tracker.getValidateCount());
            } else {
                assertEquals("Instance " +  tracker.getId() + 
                        " visited wrong number of times.",
                        2, tracker.getValidateCount());
            }
        } 
        // Randomly generate a pools with random numTests
        // and make sure evictor cycles through elements appropriately
        int[] smallPrimes = {2, 3, 5, 7};
        Random random = new Random();
        random.setSeed(System.currentTimeMillis());
        pool.setMaxIdle(-1);
        for (int i = 0; i < 4; i++) {
            pool.setNumTestsPerEvictionRun(smallPrimes[i]);
            for (int j = 0; j < 5; j++) {
                pool.clear();
                int instanceCount = 10 + random.nextInt(20);
                for (int k = 0; k < instanceCount; k++) {
                    pool.addObject();
                }

                // Execute a random number of evictor runs
                int runs = 10 + random.nextInt(50);
                for (int k = 0; k < runs; k++) {
                    pool.evict();
                }

                // Number of times evictor should have cycled through the pool
                int cycleCount = (runs * pool.getNumTestsPerEvictionRun())
                / instanceCount;

                // Look at elements and make sure they are visited cycleCount
                // or cycleCount + 1 times
                VisitTracker tracker = null;
                int visitCount = 0;
                for (int k = 0; k < instanceCount; k++) {
                    tracker = (VisitTracker) pool.borrowObject(); 
                    visitCount = tracker.getValidateCount();                  
                    assertTrue(visitCount >= cycleCount && 
                            visitCount <= cycleCount + 1);
                }
            }
        }
    }

    public void testExceptionOnPassivateDuringReturn() throws Exception {
        SimpleFactory factory = new SimpleFactory();        
        GenericObjectPool pool = new GenericObjectPool(factory);
        Object obj = pool.borrowObject();
        factory.setThrowExceptionOnPassivate(true);
        pool.returnObject(obj);
        assertEquals(0,pool.getNumIdle());
        pool.close();
    }

    public void testSetFactoryWithActiveObjects() throws Exception {
        GenericObjectPool pool = new GenericObjectPool();
        pool.setMaxIdle(10);
        pool.setFactory(new SimpleFactory());
        Object obj = pool.borrowObject();
        assertNotNull(obj);
        try {
            pool.setFactory(null);
            fail("Expected IllegalStateException");
        } catch(IllegalStateException e) {
            // expected
        }
        try {
            pool.setFactory(new SimpleFactory());
            fail("Expected IllegalStateException");
        } catch(IllegalStateException e) {
            // expected
        }
    }

    public void testSetFactoryWithNoActiveObjects() throws Exception {
        GenericObjectPool pool = new GenericObjectPool();
        pool.setMaxIdle(10);
        pool.setFactory(new SimpleFactory());
        Object obj = pool.borrowObject();
        pool.returnObject(obj);
        assertEquals(1,pool.getNumIdle());
        pool.setFactory(new SimpleFactory());
        assertEquals(0,pool.getNumIdle());
    }
    
    public void testNegativeMaxActive() throws Exception {
        pool.setMaxActive(-1);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);
        Object obj = pool.borrowObject();
        assertEquals(getNthObject(0),obj);
        pool.returnObject(obj);
    }

    public void testMaxIdle() throws Exception {
        pool.setMaxActive(100);
        pool.setMaxIdle(8);
        Object[] active = new Object[100];
        for(int i=0;i<100;i++) {
            active[i] = pool.borrowObject();
        }
        assertEquals(100,pool.getNumActive());
        assertEquals(0,pool.getNumIdle());
        for(int i=0;i<100;i++) {
            pool.returnObject(active[i]);
            assertEquals(99 - i,pool.getNumActive());
            assertEquals((i < 8 ? i+1 : 8),pool.getNumIdle());
        }
    }

    public void testMaxIdleZero() throws Exception {
        pool.setMaxActive(100);
        pool.setMaxIdle(0);
        Object[] active = new Object[100];
        for(int i=0;i<100;i++) {
            active[i] = pool.borrowObject();
        }
        assertEquals(100,pool.getNumActive());
        assertEquals(0,pool.getNumIdle());
        for(int i=0;i<100;i++) {
            pool.returnObject(active[i]);
            assertEquals(99 - i,pool.getNumActive());
            assertEquals(0, pool.getNumIdle());
        }
    }

    public void testMaxActive() throws Exception {
        pool.setMaxActive(3);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);

        pool.borrowObject();
        pool.borrowObject();
        pool.borrowObject();
        try {
            pool.borrowObject();
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    public void testMaxActiveZero() throws Exception {
        pool.setMaxActive(0);
        pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);

        try {
            pool.borrowObject();
            fail("Expected NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    public void testInvalidWhenExhaustedAction() throws Exception {
        try {
            pool.setWhenExhaustedAction(Byte.MAX_VALUE);
            fail("Expected IllegalArgumentException");
        } catch(IllegalArgumentException e) {
            // expected
        }

        try {
            ObjectPool pool = new GenericObjectPool(
                new SimpleFactory(),
                GenericObjectPool.DEFAULT_MAX_ACTIVE, 
                Byte.MAX_VALUE,
                GenericObjectPool.DEFAULT_MAX_WAIT, 
                GenericObjectPool.DEFAULT_MAX_IDLE,
                false,
                false,
                GenericObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
                GenericObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
                GenericObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
                false
            );
            assertNotNull(pool);
            fail("Expected IllegalArgumentException");
        } catch(IllegalArgumentException e) {
            // expected
        }
    }

    public void testSettersAndGetters() throws Exception {
        GenericObjectPool pool = new GenericObjectPool();
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
            pool.setSoftMinEvictableIdleTimeMillis(12135L);
            assertEquals(12135L,pool.getSoftMinEvictableIdleTimeMillis());
        }
        {
            pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_BLOCK,pool.getWhenExhaustedAction());
            pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_FAIL);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_FAIL,pool.getWhenExhaustedAction());
            pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_GROW);
            assertEquals(GenericObjectPool.WHEN_EXHAUSTED_GROW,pool.getWhenExhaustedAction());
        }
    }
    
    public void testDefaultConfiguration() throws Exception {
        GenericObjectPool pool = new GenericObjectPool();
        assertConfiguration(new GenericObjectPool.Config(),pool);
    }

    public void testConstructors() throws Exception {
        {
            GenericObjectPool pool = new GenericObjectPool();
            assertConfiguration(new GenericObjectPool.Config(),pool);
        }
        {
            GenericObjectPool pool = new GenericObjectPool(new SimpleFactory());
            assertConfiguration(new GenericObjectPool.Config(),pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxIdle = 3;
            expected.maxWait = 5L;
            expected.minEvictableIdleTimeMillis = 7L;
            expected.numTestsPerEvictionRun = 9;
            expected.testOnBorrow = true;
            expected.testOnReturn = true;
            expected.testWhileIdle = true;
            expected.timeBetweenEvictionRunsMillis = 11L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxWait = 5L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive,expected.whenExhaustedAction,expected.maxWait);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxWait = 5L;
            expected.testOnBorrow = true;
            expected.testOnReturn = true;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive,expected.whenExhaustedAction,expected.maxWait,expected.testOnBorrow,expected.testOnReturn);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxIdle = 3;
            expected.maxWait = 5L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive,expected.whenExhaustedAction,expected.maxWait,expected.maxIdle);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxIdle = 3;
            expected.maxWait = 5L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            expected.testOnBorrow = true;
            expected.testOnReturn = true;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive,expected.whenExhaustedAction,expected.maxWait,expected.maxIdle,expected.testOnBorrow,expected.testOnReturn);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxIdle = 3;
            expected.maxWait = 5L;
            expected.minEvictableIdleTimeMillis = 7L;
            expected.numTestsPerEvictionRun = 9;
            expected.testOnBorrow = true;
            expected.testOnReturn = true;
            expected.testWhileIdle = true;
            expected.timeBetweenEvictionRunsMillis = 11L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive, expected.whenExhaustedAction, expected.maxWait, expected.maxIdle, expected.testOnBorrow, expected.testOnReturn, expected.timeBetweenEvictionRunsMillis, expected.numTestsPerEvictionRun, expected.minEvictableIdleTimeMillis, expected.testWhileIdle);
            assertConfiguration(expected,pool);
        }
        {
            GenericObjectPool.Config expected = new GenericObjectPool.Config();
            expected.maxActive = 2;
            expected.maxIdle = 3;
            expected.minIdle = 1;
            expected.maxWait = 5L;
            expected.minEvictableIdleTimeMillis = 7L;
            expected.numTestsPerEvictionRun = 9;
            expected.testOnBorrow = true;
            expected.testOnReturn = true;
            expected.testWhileIdle = true;
            expected.timeBetweenEvictionRunsMillis = 11L;
            expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
            GenericObjectPool pool = new GenericObjectPool(null,expected.maxActive, expected.whenExhaustedAction, expected.maxWait, expected.maxIdle, expected.minIdle, expected.testOnBorrow, expected.testOnReturn, expected.timeBetweenEvictionRunsMillis, expected.numTestsPerEvictionRun, expected.minEvictableIdleTimeMillis, expected.testWhileIdle);
            assertConfiguration(expected,pool);
        }
    }

    public void testSetConfig() throws Exception {
        GenericObjectPool.Config expected = new GenericObjectPool.Config();
        GenericObjectPool pool = new GenericObjectPool();
        assertConfiguration(expected,pool);
        expected.maxActive = 2;
        expected.maxIdle = 3;
        expected.maxWait = 5L;
        expected.minEvictableIdleTimeMillis = 7L;
        expected.numTestsPerEvictionRun = 9;
        expected.testOnBorrow = true;
        expected.testOnReturn = true;
        expected.testWhileIdle = true;
        expected.timeBetweenEvictionRunsMillis = 11L;
        expected.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_GROW;
        pool.setConfig(expected);
        assertConfiguration(expected,pool);
    }

    public void testDebugInfo() throws Exception {
        GenericObjectPool pool = new GenericObjectPool(new SimpleFactory());
        pool.setMaxIdle(3);
        assertNotNull(pool.debugInfo());
        Object obj = pool.borrowObject();
        assertNotNull(pool.debugInfo());
        pool.returnObject(obj);
        assertNotNull(pool.debugInfo());
    }

    public void testStartAndStopEvictor() throws Exception {
        // set up pool without evictor
        pool.setMaxIdle(6);
        pool.setMaxActive(6);
        pool.setNumTestsPerEvictionRun(6);
        pool.setMinEvictableIdleTimeMillis(100L);

        for(int j=0;j<2;j++) {
            // populate the pool
            {
                Object[] active = new Object[6];
                for(int i=0;i<6;i++) {
                    active[i] = pool.borrowObject();
                }
                for(int i=0;i<6;i++) {
                    pool.returnObject(active[i]);
                }
            }
    
            // note that it stays populated
            assertEquals("Should have 6 idle",6,pool.getNumIdle());
    
            // start the evictor
            pool.setTimeBetweenEvictionRunsMillis(50L);
            
            // wait a second (well, .2 seconds)
            try { Thread.sleep(200L); } catch(Exception e) { }
            
            // assert that the evictor has cleared out the pool
            assertEquals("Should have 0 idle",0,pool.getNumIdle());
    
            // stop the evictor 
            pool.startEvictor(0L);
        }
    }

    public void testEvictionWithNegativeNumTests() throws Exception {
        // when numTestsPerEvictionRun is negative, it represents a fraction of the idle objects to test
        pool.setMaxIdle(6);
        pool.setMaxActive(6);
        pool.setNumTestsPerEvictionRun(-2);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(100L);

        Object[] active = new Object[6];
        for(int i=0;i<6;i++) {
            active[i] = pool.borrowObject();
        }
        for(int i=0;i<6;i++) {
            pool.returnObject(active[i]);
        }

        try { Thread.sleep(100L); } catch(Exception e) { }
        assertTrue("Should at most 6 idle, found " + pool.getNumIdle(),pool.getNumIdle() <= 6);
        try { Thread.sleep(100L); } catch(Exception e) { }
        assertTrue("Should at most 3 idle, found " + pool.getNumIdle(),pool.getNumIdle() <= 3);
        try { Thread.sleep(100L); } catch(Exception e) { }
        assertTrue("Should be at most 2 idle, found " + pool.getNumIdle(),pool.getNumIdle() <= 2);
        try { Thread.sleep(100L); } catch(Exception e) { }
        assertEquals("Should be zero idle, found " + pool.getNumIdle(),0,pool.getNumIdle());
    }

    public void testEviction() throws Exception {
        pool.setMaxIdle(500);
        pool.setMaxActive(500);
        pool.setNumTestsPerEvictionRun(100);
        pool.setMinEvictableIdleTimeMillis(250L);
        pool.setTimeBetweenEvictionRunsMillis(500L);
        pool.setTestWhileIdle(true);

        Object[] active = new Object[500];
        for(int i=0;i<500;i++) {
            active[i] = pool.borrowObject();
        }
        for(int i=0;i<500;i++) {
            pool.returnObject(active[i]);
        }

        try { Thread.sleep(1000L); } catch(Exception e) { }
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

        for(int i=0;i<500;i++) {
            active[i] = pool.borrowObject();
        }
        for(int i=0;i<500;i++) {
            pool.returnObject(active[i]);
        }

        try { Thread.sleep(1000L); } catch(Exception e) { }
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
     * This test has a number of problems.
     * 1. without the wait code in the for loop the create time for each instance
     * was usually the same due to clock presision.
     * 2. It's very hard to follow.
     */
    public void DISABLEDtestEvictionSoftMinIdle() throws Exception {
        GenericObjectPool pool = null;
        
        class TimeTest extends BasePoolableObjectFactory {
            private final long createTime;
            public TimeTest() {
                createTime = System.currentTimeMillis();
            }
            public Object makeObject() throws Exception {
                return new TimeTest();
            }
            public long getCreateTime() {
                return createTime;
            }
        }
        
        pool = new GenericObjectPool(new TimeTest());
        
        pool.setMaxIdle(5);
        pool.setMaxActive(5);
        pool.setNumTestsPerEvictionRun(5);
        pool.setMinEvictableIdleTimeMillis(3000L);
        pool.setTimeBetweenEvictionRunsMillis(250L);
        pool.setTestWhileIdle(true);
        pool.setSoftMinEvictableIdleTimeMillis(1000L);
        pool.setMinIdle(2);
        
        Object[] active = new Object[5];
        Long[] creationTime = new Long[5] ;
        long lastCreationTime = System.currentTimeMillis();
        for(int i=0;i<5;i++) {
            // make sure each instance has a different currentTimeMillis()
            while (lastCreationTime == System.currentTimeMillis()) {
                Thread.sleep(1);
            }
            active[i] = pool.borrowObject();
            creationTime[i] = new Long(((TimeTest)active[i]).getCreateTime());
            lastCreationTime = creationTime[i].longValue();
        }
        
        for(int i=0;i<5;i++) {
            pool.returnObject(active[i]);
        }
        
        try { Thread.sleep(1500L); } catch(Exception e) { }
        assertTrue("Should be 2 OLD idle, found " + pool.getNumIdle(),pool.getNumIdle() == 2 &&
                ((TimeTest)pool.borrowObject()).getCreateTime() == creationTime[3].longValue() &&
                ((TimeTest)pool.borrowObject()).getCreateTime() == creationTime[4].longValue());
        
        try { Thread.sleep(2000L); } catch(Exception e) { }
        assertTrue("Should be 2 NEW idle , found " + pool.getNumIdle(),pool.getNumIdle() == 2 &&
                ((TimeTest)pool.borrowObject()).getCreateTime() != creationTime[0].longValue() &&
                ((TimeTest)pool.borrowObject()).getCreateTime() != creationTime[1].longValue());
    }

    public void testMinIdle() throws Exception {
        pool.setMaxIdle(500);
        pool.setMinIdle(5);
        pool.setMaxActive(10);
        pool.setNumTestsPerEvictionRun(0);
        pool.setMinEvictableIdleTimeMillis(50L);
        pool.setTimeBetweenEvictionRunsMillis(100L);
        pool.setTestWhileIdle(true);

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        Object[] active = new Object[5];
        active[0] = pool.borrowObject();

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=1 ; i<5 ; i++) {
            active[i] = pool.borrowObject();
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            pool.returnObject(active[i]);
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

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        Object[] active = new Object[10];

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            active[i] = pool.borrowObject();
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 5 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 5);

        for(int i=0 ; i<5 ; i++) {
            pool.returnObject(active[i]);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 10 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 10);

        for(int i=0 ; i<10 ; i++) {
            active[i] = pool.borrowObject();
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 0 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 0);

        for(int i=0 ; i<10 ; i++) {
            pool.returnObject(active[i]);
        }

        try { Thread.sleep(150L); } catch(Exception e) { }
        assertTrue("Should be 10 idle, found " + pool.getNumIdle(),pool.getNumIdle() == 10);
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
     * Verifies that maxActive is not exceeded when factory destroyObject
     * has high latency, testOnReturn is set and there is high incidence of
     * validation failures. 
     */
    public void testMaxActiveInvariant() throws Exception {
        int maxActive = 15;
        SimpleFactory factory = new SimpleFactory();
        factory.setEvenValid(false);     // Every other validation fails
        factory.setDestroyLatency(100);  // Destroy takes 100 ms
        factory.setMaxActive(maxActive); // (makes - destroys) bound
        factory.setValidationEnabled(true);
        pool = new GenericObjectPool(factory);
        pool.setMaxActive(maxActive);
        pool.setMaxIdle(-1);
        pool.setTestOnReturn(true);
        pool.setMaxWait(1000L);
        runTestThreads(5, 10, 50);
    }

    class TestThread implements Runnable {
        java.util.Random _random = new java.util.Random();
        ObjectPool _pool = null;
        boolean _complete = false;
        boolean _failed = false;
        int _iter = 100;
        int _delay = 50;
        boolean _randomDelay = true;

        public TestThread(ObjectPool pool) {
            _pool = pool;
        }

        public TestThread(ObjectPool pool, int iter) {
            _pool = pool;
            _iter = iter;
        }

        public TestThread(ObjectPool pool, int iter, int delay) {
            _pool = pool;
            _iter = iter;
            _delay = delay;
        }
        
        public TestThread(ObjectPool pool, int iter, int delay,
                boolean randomDelay) {
            _pool = pool;
            _iter = iter;
            _delay = delay;
            _randomDelay = randomDelay;
        }

        public boolean complete() {
            return _complete;
        }

        public boolean failed() {
            return _failed;
        }

        public void run() {
            for(int i=0;i<_iter;i++) {
                long delay = 
                    _randomDelay ? (long)_random.nextInt(_delay) : _delay;
                try {
                    Thread.sleep(delay);
                } catch(Exception e) {
                    // ignored
                }
                Object obj = null;
                try {
                    obj = _pool.borrowObject();
                } catch(Exception e) {
                    e.printStackTrace();
                    _failed = true;
                    _complete = true;
                    break;
                }

                try {
                    Thread.sleep(delay);
                } catch(Exception e) {
                    // ignored
                }
                try {
                    _pool.returnObject(obj);
                } catch(Exception e) {
                    _failed = true;
                    _complete = true;
                    break;
                }
            }
            _complete = true;
        }
    }

    public void testFIFO() throws Exception {
        pool.setLifo(false);
        pool.addObject(); // "0"
        pool.addObject(); // "1"
        pool.addObject(); // "2"
        assertEquals("Oldest", "0", pool.borrowObject());
        assertEquals("Middle", "1", pool.borrowObject());
        assertEquals("Youngest", "2", pool.borrowObject());
        assertEquals("new-3", "3", pool.borrowObject());
        pool.returnObject("r");
        assertEquals("returned", "r", pool.borrowObject());
        assertEquals("new-4", "4", pool.borrowObject());
    }
    
    public void testLIFO() throws Exception {
        pool.setLifo(true);
        pool.addObject(); // "0"
        pool.addObject(); // "1"
        pool.addObject(); // "2"
        assertEquals("Youngest", "2", pool.borrowObject());
        assertEquals("Middle", "1", pool.borrowObject());
        assertEquals("Oldest", "0", pool.borrowObject());
        assertEquals("new-3", "3", pool.borrowObject());
        pool.returnObject("r");
        assertEquals("returned", "r", pool.borrowObject());
        assertEquals("new-4", "4", pool.borrowObject());
    }

    public void testAddObject() throws Exception {
        assertEquals("should be zero idle", 0, pool.getNumIdle());
    	pool.addObject();
		assertEquals("should be one idle", 1, pool.getNumIdle());
		assertEquals("should be zero active", 0, pool.getNumActive());
		Object obj = pool.borrowObject();
		assertEquals("should be zero idle", 0, pool.getNumIdle());
		assertEquals("should be one active", 1, pool.getNumActive());
		pool.returnObject(obj);
		assertEquals("should be one idle", 1, pool.getNumIdle());
		assertEquals("should be zero active", 0, pool.getNumActive());

        ObjectPool op = new GenericObjectPool();
        try {
            op.addObject();
            fail("Expected IllegalStateException when there is no factory.");
        } catch (IllegalStateException ise) {
            //expected
        }
        op.close();
    }
    
    protected GenericObjectPool pool = null;

    private void assertConfiguration(GenericObjectPool.Config expected, GenericObjectPool actual) throws Exception {
        assertEquals("testOnBorrow",expected.testOnBorrow,actual.getTestOnBorrow());
        assertEquals("testOnReturn",expected.testOnReturn,actual.getTestOnReturn());
        assertEquals("testWhileIdle",expected.testWhileIdle,actual.getTestWhileIdle());
        assertEquals("whenExhaustedAction",expected.whenExhaustedAction,actual.getWhenExhaustedAction());
        assertEquals("maxActive",expected.maxActive,actual.getMaxActive());
        assertEquals("maxIdle",expected.maxIdle,actual.getMaxIdle());
        assertEquals("maxWait",expected.maxWait,actual.getMaxWait());
        assertEquals("minEvictableIdleTimeMillis",expected.minEvictableIdleTimeMillis,actual.getMinEvictableIdleTimeMillis());
        assertEquals("numTestsPerEvictionRun",expected.numTestsPerEvictionRun,actual.getNumTestsPerEvictionRun());
        assertEquals("timeBetweenEvictionRunsMillis",expected.timeBetweenEvictionRunsMillis,actual.getTimeBetweenEvictionRunsMillis());
    }

    public class SimpleFactory implements PoolableObjectFactory {
        public SimpleFactory() {
            this(true);
        }
        public SimpleFactory(boolean valid) {
            this(valid,valid);
        }
        public SimpleFactory(boolean evalid, boolean ovalid) {
            evenValid = evalid;
            oddValid = ovalid;
        }
        void setValid(boolean valid) {
            setEvenValid(valid);
            setOddValid(valid);            
        }
        void setEvenValid(boolean valid) {
            evenValid = valid;
        }
        void setOddValid(boolean valid) {
            oddValid = valid;
        }
        public void setThrowExceptionOnPassivate(boolean bool) {
            exceptionOnPassivate = bool;
        }
        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }
        public void setDestroyLatency(long destroyLatency) {
            this.destroyLatency = destroyLatency;
        }
        public void setMakeLatency(long makeLatency) {
            this.makeLatency = makeLatency;
        }
        public Object makeObject() { 
            synchronized(this) {
                activeCount++;
                if (activeCount > maxActive) {
                    throw new IllegalStateException(
                        "Too many active instances: " + activeCount);
                }
            }
            if (makeLatency > 0) {
                doWait(makeLatency);
            }
            return String.valueOf(makeCounter++);
        }
        public void destroyObject(Object obj) {
            if (destroyLatency > 0) {
                doWait(destroyLatency);
            }
            synchronized(this) {
                activeCount--;
            }
        }
        public boolean validateObject(Object obj) {
            if (enableValidation) { 
                return validateCounter++%2 == 0 ? evenValid : oddValid; 
            }
            else {
                return true;
            }
        }
        public void activateObject(Object obj) throws Exception {
            if (exceptionOnActivate) {
                if (!(validateCounter++%2 == 0 ? evenValid : oddValid)) {
                    throw new Exception();
                }
            }
        }
        public void passivateObject(Object obj) throws Exception {
            if(exceptionOnPassivate) {
                throw new Exception();
            }
        }
        int makeCounter = 0;
        int validateCounter = 0;
        int activeCount = 0;
        boolean evenValid = true;
        boolean oddValid = true;
        boolean exceptionOnPassivate = false;
        boolean exceptionOnActivate = false;
        boolean enableValidation = true;
        long destroyLatency = 0;
        long makeLatency = 0;
        int maxActive = Integer.MAX_VALUE;

        public boolean isThrowExceptionOnActivate() {
            return exceptionOnActivate;
        }

        public void setThrowExceptionOnActivate(boolean b) {
            exceptionOnActivate = b;
        }

        public boolean isValidationEnabled() {
            return enableValidation;
        }

        public void setValidationEnabled(boolean b) {
            enableValidation = b;
        }
        
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


