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

import org.apache.commons.pool.TestKeyedObjectPoolFactory;
import org.apache.commons.pool.KeyedObjectPoolFactory;
import org.apache.commons.pool.KeyedPoolableObjectFactory;
import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * Tests for {@link StackKeyedObjectPoolFactory}.
 *
 * @author Sandy McArthur
 * @version $Revision: 480413 $ $Date: 2006-11-28 22:16:05 -0700 (Tue, 28 Nov 2006) $
 */
public class TestStackKeyedObjectPoolFactory extends TestKeyedObjectPoolFactory {
    public TestStackKeyedObjectPoolFactory(final String name) {
        super(name);
    }

    public static Test suite() {
        return new TestSuite(TestStackKeyedObjectPoolFactory.class);
    }

    protected KeyedObjectPoolFactory makeFactory(final KeyedPoolableObjectFactory objectFactory) throws UnsupportedOperationException {
        return new StackKeyedObjectPoolFactory(objectFactory);
    }

    public void testConstructors() throws Exception {
        StackKeyedObjectPoolFactory factory = new StackKeyedObjectPoolFactory();
        factory.createPool().close();

        factory = new StackKeyedObjectPoolFactory(1);
        StackKeyedObjectPool pool = (StackKeyedObjectPool)factory.createPool();
        assertEquals(1,pool._maxSleeping);
        pool.close();

        factory = new StackKeyedObjectPoolFactory(1, 2);
        pool = (StackKeyedObjectPool)factory.createPool();
        assertEquals(1,pool._maxSleeping);
        assertEquals(2,pool._initSleepingCapacity);
        pool.close();

        factory = new StackKeyedObjectPoolFactory(createObjectFactory());
        pool = (StackKeyedObjectPool)factory.createPool();
        pool.close();

        factory = new StackKeyedObjectPoolFactory(createObjectFactory(),  1);
        pool = (StackKeyedObjectPool)factory.createPool();
        assertEquals(1,pool._maxSleeping);
        pool.close();

        factory = new StackKeyedObjectPoolFactory(createObjectFactory(),  1, 2);
        pool = (StackKeyedObjectPool)factory.createPool();
        assertEquals(1,pool._maxSleeping);
        assertEquals(2,pool._initSleepingCapacity);
        pool.close();

    }
}
