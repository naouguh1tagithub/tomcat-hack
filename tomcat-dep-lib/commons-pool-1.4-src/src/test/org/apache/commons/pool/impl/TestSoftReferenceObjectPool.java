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

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.commons.pool.TestObjectPool;
import org.apache.commons.pool.TestBaseObjectPool;

/**
 * @author Rodney Waldhoff
 * @author Sandy McArthur
 * @version $Revision: 480413 $ $Date: 2006-11-28 22:16:05 -0700 (Tue, 28 Nov 2006) $
 */
public class TestSoftReferenceObjectPool extends TestBaseObjectPool {
    public TestSoftReferenceObjectPool(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestSoftReferenceObjectPool.class);
    }

    protected ObjectPool makeEmptyPool(int cap) {
        return new SoftReferenceObjectPool(
            new PoolableObjectFactory()  {
                int counter = 0;
                public Object makeObject() { return String.valueOf(counter++); }
                public void destroyObject(Object obj) { }
                public boolean validateObject(Object obj) { return true; }
                public void activateObject(Object obj) { }
                public void passivateObject(Object obj) { }
            }
            );
    }

    protected ObjectPool makeEmptyPool(final PoolableObjectFactory factory) {
        return new SoftReferenceObjectPool(factory);
    }

    protected Object getNthObject(int n) {
        return String.valueOf(n);
    }

    protected boolean isLifo() {
        return false;
    }

    protected boolean isFifo() {
        return false;
    }

}
