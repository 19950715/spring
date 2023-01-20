/*
 * Copyright 2002-2015 the original author or authors.
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

import java.io.Serializable;
import java.lang.reflect.Proxy;

import org.springframework.aop.SpringProxy;

/**
 * Default {@link AopProxyFactory} implementation, creating either a CGLIB proxy
 * or a JDK dynamic proxy.
 *
 * <p>Creates a CGLIB proxy if one the following is true for a given
 * {@link AdvisedSupport} instance:
 * <ul>
 * <li>the {@code optimize} flag is set
 * <li>the {@code proxyTargetClass} flag is set
 * <li>no proxy interfaces have been specified
 * </ul>
 *
 * <p>In general, specify {@code proxyTargetClass} to enforce a CGLIB proxy,
 * or specify one or more interfaces to use a JDK dynamic proxy.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @since 12.03.2004
 * @see AdvisedSupport#setOptimize
 * @see AdvisedSupport#setProxyTargetClass
 * @see AdvisedSupport#setInterfaces
 */
@SuppressWarnings("serial")
public class DefaultAopProxyFactory implements AopProxyFactory, Serializable {

	/**
	 * Whether this environment lives within a native image.
	 * Exposed as a private static field rather than in a {@code NativeImageDetector.inNativeImage()} static method due to https://github.com/oracle/graal/issues/2594.
	 * @see <a href="https://github.com/oracle/graal/blob/master/sdk/src/org.graalvm.nativeimage/src/org/graalvm/nativeimage/ImageInfo.java">ImageInfo.java</a>
	 */
	private static final boolean IN_NATIVE_IMAGE = (System.getProperty("org.graalvm.nativeimage.imagecode") != null);


	/**
	 * 判断使用jdk还是cglib动态代理
	 * @param config the AOP configuration in the form of an
	 * AdvisedSupport object
	 * @return
	 * @throws AopConfigException
	 */
	@Override
	public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
		//如果isOptimize返回true，即optimize属性为true，表示CGLIB代理应该主动进行优化，默认false
		//或者，如果isProxyTargetClass返回true，即proxyTargetClass属性为true，表示应该使用CGLIB代理，默认false
		//或者，如果hasNoUserSuppliedProxyInterfaces返回true，表示没有可使用的代理接口或者只有一个代理接口并且属于SpringProxy接口体系
		//即校验interfaces集合，这个集合就是在此前evaluateProxyInterfaces方法中加入的接口集合
		if (!IN_NATIVE_IMAGE &&
				(config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config))) {
			//获取目标类型
			Class<?> targetClass = config.getTargetClass();
			if (targetClass == null) {
				throw new AopConfigException("TargetSource cannot determine target class: " +
						"Either an interface or a target is required for proxy creation.");
			}
			// 如果要代理的类本身就是接口，则使用 JDK 动态代理
			//如果目标类型是接口，或者目标类型就是Proxy类型
			if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)) {
				//那么采用JDK的AopProxy，proxyFactory作为构造器参数
				return new JdkDynamicAopProxy(config);
			}
			//默认采用CGLIB的AopProxy，proxyFactory作为构造器参数
			return new ObjenesisCglibAopProxy(config);
		}
		else {
			// 如果有接口，会跑到这个分支
			//以上三个条件都不满足
			//否则，采用JDK的AopProxy，proxyFactory作为构造器参数
			return new JdkDynamicAopProxy(config);
		}
	}

	/**
	 * Determine whether the supplied {@link AdvisedSupport} has only the
	 * {@link org.springframework.aop.SpringProxy} interface specified
	 * (or no proxy interfaces specified at all).
	 */
	private boolean hasNoUserSuppliedProxyInterfaces(AdvisedSupport config) {
		//获取内部的interfaces集合，这个集合就是在此前evaluateProxyInterfaces方法中加入的接口集合
		Class<?>[] ifcs = config.getProxiedInterfaces();
		//如果是一个空集合，表示没有可使用的代理接口，或者只有一个代理接口并且属于SpringProxy接口体系，那么返回true
		return (ifcs.length == 0 || (ifcs.length == 1 && SpringProxy.class.isAssignableFrom(ifcs[0])));
	}

}
