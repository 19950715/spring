/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.framework;

import java.lang.reflect.Constructor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.util.ReflectionUtils;

/**
 * Objenesis-based extension of {@link CglibAopProxy} to create proxy instances
 * without invoking the constructor of the class. Used by default as of Spring 4.
 *
 * @author Oliver Gierke
 * @author Juergen Hoeller
 * @since 4.0
 */
@SuppressWarnings("serial")
class ObjenesisCglibAopProxy extends CglibAopProxy {

	private static final Log logger = LogFactory.getLog(ObjenesisCglibAopProxy.class);

	private static final SpringObjenesis objenesis = new SpringObjenesis();


	/**
	 * Create a new ObjenesisCglibAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 */
	public ObjenesisCglibAopProxy(AdvisedSupport config) {
		super(config);
	}


	// * 根据enhancer和callbacks生成代理类并创建代理实例
	@Override
	protected Object createProxyClassAndInstance(Enhancer enhancer, Callback[] callbacks) {
		//通过Enhancer创建代理子类class
		Class<?> proxyClass = enhancer.createClass();
		Object proxyInstance = null;
		//通过判断objenesis的worthTrying属性是否不等于Boolean.FALSE对象，来确定是否值得通过Objenesis来生成代理类的实例
		//worthTrying属性默认为null，不等于Boolean.FALSE对象，因此一般都是走这个逻辑
		if (objenesis.isWorthTrying()) {
			try {
				//通过objenesis绕过构造器创建代理类对象，即不需要调用任何构造器
				proxyInstance = objenesis.newInstance(proxyClass, enhancer.getUseCache());
			}
			catch (Throwable ex) {
				logger.debug("Unable to instantiate proxy using Objenesis, " +
						"falling back to regular proxy construction", ex);
			}
		}
		//如果proxyInstance还是为null，那么尝试通过反射代理类的无参构造器创建代理类对象
		if (proxyInstance == null) {
			// Regular instantiation via default constructor...
			try {
				//如果没手动设置constructorArgs构造器参数，默认不会设置，那么获取无参构造器，否则获取对应参数的构造器
				Constructor<?> ctor = (this.constructorArgs != null ?
						proxyClass.getDeclaredConstructor(this.constructorArgTypes) :
						proxyClass.getDeclaredConstructor());
				//设置构造器的可访问属性，即ctor.setAccessible(true)
				ReflectionUtils.makeAccessible(ctor);
				//根据是否手动设置了构造器参数调用相关反射方法创建代理类的实例
				proxyInstance = (this.constructorArgs != null ?
						ctor.newInstance(this.constructorArgs) : ctor.newInstance());
			}
			catch (Throwable ex) {
				throw new AopConfigException("Unable to instantiate proxy using Objenesis, " +
						"and regular proxy instantiation via default constructor fails as well", ex);
			}
		}
		//设置拦截器链，返回代理类实例
		((Factory) proxyInstance).setCallbacks(callbacks);
		return proxyInstance;
	}

}
