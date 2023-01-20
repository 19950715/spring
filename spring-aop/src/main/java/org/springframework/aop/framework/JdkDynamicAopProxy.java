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

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.AopInvocationException;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DecoratingProxy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * JDK-based {@link AopProxy} implementation for the Spring AOP framework,
 * based on JDK {@link java.lang.reflect.Proxy dynamic proxies}.
 *
 * <p>Creates a dynamic proxy, implementing the interfaces exposed by
 * the AopProxy. Dynamic proxies <i>cannot</i> be used to proxy methods
 * defined in classes, rather than interfaces.
 *
 * <p>Objects of this type should be obtained through proxy factories,
 * configured by an {@link AdvisedSupport} class. This class is internal
 * to Spring's AOP framework and need not be used directly by client code.
 *
 * <p>Proxies created using this class will be thread-safe if the
 * underlying (target) class is thread-safe.
 *
 * <p>Proxies are serializable so long as all Advisors (including Advices
 * and Pointcuts) and the TargetSource are serializable.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @see java.lang.reflect.Proxy
 * @see AdvisedSupport
 * @see ProxyFactory
 */
final class JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable {

	/** use serialVersionUID from Spring 1.2 for interoperability. */
	private static final long serialVersionUID = 5531744639992436476L;


	/*
	 * NOTE: We could avoid the code duplication between this class and the CGLIB
	 * proxies by refactoring "invoke" into a template method. However, this approach
	 * adds at least 10% performance overhead versus a copy-paste solution, so we sacrifice
	 * elegance for performance. (We have a good test suite to ensure that the different
	 * proxies behave the same :-)
	 * This way, we can also more easily take advantage of minor optimizations in each class.
	 */

	/** We use a static Log to avoid serialization issues. */
	private static final Log logger = LogFactory.getLog(JdkDynamicAopProxy.class);

	/** Config used to configure this proxy. */
	private final AdvisedSupport advised;

	/**
	 * Is the {@link #equals} method defined on the proxied interfaces?
	 */
	private boolean equalsDefined;

	/**
	 * Is the {@link #hashCode} method defined on the proxied interfaces?
	 */
	private boolean hashCodeDefined;


	/**
	 * Construct a new JdkDynamicAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 * @throws AopConfigException if the config is invalid. We try to throw an informative
	 * exception in this case, rather than let a mysterious failure happen later.
	 */
	public JdkDynamicAopProxy(AdvisedSupport config) throws AopConfigException {
		Assert.notNull(config, "AdvisedSupport must not be null");
		if (config.getAdvisors().length == 0 && config.getTargetSource() == AdvisedSupport.EMPTY_TARGET_SOURCE) {
			throw new AopConfigException("No advisors and no TargetSource specified");
		}
		this.advised = config;
	}


	@Override
	public Object getProxy() {
		return getProxy(ClassUtils.getDefaultClassLoader());
	}

	@Override
	public Object getProxy(@Nullable ClassLoader classLoader) {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating JDK dynamic proxy: " + this.advised.getTargetSource());
		}
		//获取全部需要代理的接口数组，主要目的是判断如果interfaces集合（evaluateProxyInterfaces方法中加入的接口集合）中没有
		//SpringProxy、Advised、DecoratingProxy这些接口，则尝试将SpringProxy、Advised、DecoratingProxy
		//这三个接口添加到数组后三位，原本的interfaces集合中缺哪一个就添加哪一个，这表示新创建代理对象将实现这些接口
		Class<?>[] proxiedInterfaces = AopProxyUtils.completeProxiedInterfaces(this.advised, true);
		//查找接口集中是否有接口存在equals方法或者hashCode方法，有的话分别标记equalsDefined和hashCodeDefined字段
		findDefinedEqualsAndHashCodeMethods(proxiedInterfaces);
		//通过Proxy创建代理对象，这是JDK自带的方式
		//第一个参数是类加载器，第二个参数就是代理类对象需要实现的接口数组，第三个参数就是当前JdkDynamicAopProxy对象，作为调用处理程序
		//因此当调用代理对象的方法时，将会方法调用被分派到调用处理程序的invoke方法，即JdkDynamicAopProxy对象的invoke方法
		return Proxy.newProxyInstance(classLoader, proxiedInterfaces, this);
	}

	/**
	 * Finds any {@link #equals} or {@link #hashCode} method that may be defined
	 * on the supplied set of interfaces.
	 * @param proxiedInterfaces the interfaces to introspect
	 */
	private void findDefinedEqualsAndHashCodeMethods(Class<?>[] proxiedInterfaces) {
		//遍历接口数组
		for (Class<?> proxiedInterface : proxiedInterfaces) {
			//获取当前接口自己的所有方法数组
			Method[] methods = proxiedInterface.getDeclaredMethods();
			//遍历方法数组
			for (Method method : methods) {
				//如果存在equals方法，那么equalsDefined设置为true
				if (AopUtils.isEqualsMethod(method)) {
					this.equalsDefined = true;
				}
				//如果存在hashCode方法，那么hashCodeDefined设置为true
				if (AopUtils.isHashCodeMethod(method)) {
					this.hashCodeDefined = true;
				}
				//如果找到有些集合存在这些方法，那么直接返回，不需要在查找了
				if (this.equalsDefined && this.hashCodeDefined) {
					return;
				}
			}
		}
	}


	/**
	 * Implementation of {@code InvocationHandler.invoke}.
	 * <p>Callers will see exactly the exception thrown by the target,
	 * unless a hook method throws an exception.
	 * 1。通过getInterceptorsAndDynamicInterceptionAdvice方法获取适合该方法的拦截器链集合。
	 * 2。创建一个MethodInvocation方法调用服务，随后使用MethodInvocation调用proceed()方法，通过责任链模式和方法递归调用来实现方法的代理和增强的逻辑。
	 */
	@Override
	@Nullable
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Object oldProxy = null;
		//是否设置代理上下文
		boolean setProxyContext = false;
		//获取TargetSource，targetSource实际上就是对目标对象实例进行封装的对象
		TargetSource targetSource = this.advised.targetSource;
		Object target = null;

		try {
			/*
			 * 一些特殊方法和情况的处理，它们不需要调用增强的方法
			 */
			/*
			 * 如果代理接口集中不存在equals方法，并且当前拦截的方法是equals方法
			 * 这说明目标类没有实现接口的equals方法本身
			 */
			if (!this.equalsDefined && AopUtils.isEqualsMethod(method)) {
				// The target does not implement the equals(Object) method itself.
				//调用JdkDynamicAopProxy自己的equals方法比较，因此可能会产生歧义
				return equals(args[0]);
			}
			/*
			 * 如果代理接口集中不存在hashCode方法，并且当前拦截的方法是hashCode方法
			 * 这说明目标类没有实现接口的hashCode方法本身
			 */
			else if (!this.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
				// The target does not implement the hashCode() method itself.
				//调用JdkDynamicAopProxy自己的equals方法比较，因此可能会产生歧义
				return hashCode();
			}
			/*
			 * 如果当前拦截的方法属于DecoratingProxy接口
			 * 那么这个方法肯定就是getDecoratedClass方法了
			 */
			else if (method.getDeclaringClass() == DecoratingProxy.class) {
				// There is only getDecoratedClass() declared -> dispatch to proxy config.
				// There is only getDecoratedClass() declared -> dispatch to proxy config.
				//直接调用AopProxyUtils.ultimateTargetClass方法返回代理的最终目标类型
				//并没有调用实现的getDecoratedClass方法
				return AopProxyUtils.ultimateTargetClass(this.advised);
			}
			/*
			 * 如果opaque属性为false（默认false），并且当前拦截的方法属于一个接口
			 * 并且方法当前拦截的方法所属的接口是Advised接口或者Advised的父接口
			 */
			else if (!this.advised.opaque && method.getDeclaringClass().isInterface() &&
					method.getDeclaringClass().isAssignableFrom(Advised.class)) {
				// Service invocations on ProxyConfig with the proxy config...
				//直接反射调用原始方法
				return AopUtils.invokeJoinpointUsingReflection(this.advised, method, args);
			}

			Object retVal;
			/*
			 * 如果exposeProxy属性为true（默认false），表示需要暴露代理对象
			 * 可通过<aop:config/>标签的expose-proxy属性或者@EnableAspectJAutoProxy注解的exposeProxy属性控制
			 */
			if (this.advised.exposeProxy) {
				// Make invocation available if necessary.
				/*
				 * 将proxy也就是代理对象，通过AopContext.setCurrentProxy设置到AopContext内部的ThreadLocal线程本地变量中
				 * 这样我们就能在原始方法中通过AopContext.currentProxy()方法直接获取当前的代理对象
				 * 这个设置主要用来解决同一个目标类的方法互相调用时代理不生效的问题
				 */
				oldProxy = AopContext.setCurrentProxy(proxy);
				//setProxyContext置为true
				setProxyContext = true;
			}

			// Get as late as possible to minimize the time we "own" the target,
			// in case it comes from a pool.
			/*
			 * 获取原始目标对象，也就是被代理的对象
			 */
			target = targetSource.getTarget();
			Class<?> targetClass = (target != null ? target.getClass() : null);

			// Get the interception chain for this method.
			/*
			 * 对此前设置到proxyFactory中的advisors集合中的Advisor进行筛选，因为advisors中的拦截器
			 * 只能说一定是匹配当前方法所属的类以及类中的某些方法，但并不一定匹配当前的方法，比如expression不匹配
			 * 所以这里需要获取匹配当前方法的Advisor中的拦截器链列表，用于拦截当前方法并进行增强
			 *
			 * 对于AnnotationAwareAspectJAutoProxyCreator和AspectJAwareAdvisorAutoProxyCreator自动代理创建者
			 * 第一个拦截器就是ExposeInvocationInterceptor，它是后面在extendAdvisors中加入进去的，其规则匹配所有方法
			 */
			List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);

			// Check whether we have any advice. If we don't, we can fallback on direct
			// reflective invocation of the target, and avoid creating a MethodInvocation.
			//如果针对当前方法的拦截器链为空
			if (chain.isEmpty()) {
				// We can skip creating a MethodInvocation: just invoke the target directly
				// Note that the final invoker must be an InvokerInterceptor so we know it does
				// nothing but a reflective operation on the target, and no hot swapping or fancy proxying.
				//如有必要，根据给定方法中的需要参数类型调整参数数组类型
				Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
				//直接反射通过目标类调用当前方法
				retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);
			}
			//如果针对当前方法的拦截器链不为空
			else {
				// We need to create a method invocation...
				/*创建一个MethodInvocation方法调用服务，参数为代理对象、目标对象、方法参数、目标类型、该方法的拦截器链集合*/
				MethodInvocation invocation =
						new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain);
				// Proceed to the joinpoint through the interceptor chain.
				/*
				 * 调用方法调用服务的proceed方法，进行方法的代理和增强
				 * 一般的AOP都是通过该方法执行的，这是核心方法
				 */
				retVal = invocation.proceed();
			}
			/*
			 * 对于方法返回值进行必要的类型转换，比如返回代理对象
			 */
			// Massage return value if necessary.
			//获取方法返回值类型
			Class<?> returnType = method.getReturnType();
			//如果执行结果不为null，并且返回值等于目标对象，并且返回值类型不是Object，并且返回值类型和代理类型兼容
			//并且方法所属的类的类型不是RawTargetAccess类型及其子类型
			if (retVal != null && retVal == target &&
					returnType != Object.class && returnType.isInstance(proxy) &&
					!RawTargetAccess.class.isAssignableFrom(method.getDeclaringClass())) {
				// Special case: it returned "this" and the return type of the method
				// is type-compatible. Note that we can't help if the target sets
				// a reference to itself in another returned object.
				//返回值设置为当前代理对象，也就是返回代理对象
				retVal = proxy;
			}
			//如果返回值为null，并且返回值类型不是void，并且返回值类型是基本类型，那么抛出异常
			else if (retVal == null && returnType != Void.TYPE && returnType.isPrimitive()) {
				throw new AopInvocationException(
						"Null return value from advice does not match primitive return type for: " + method);
			}
			//返回结果
			return retVal;
		}
		finally {
			if (target != null && !targetSource.isStatic()) {
				// Must have come from TargetSource.
				targetSource.releaseTarget(target);
			}
			if (setProxyContext) {
				// Restore old proxy.
				//替换oldProxy
				AopContext.setCurrentProxy(oldProxy);
			}
		}
	}


	/**
	 * Equality means interfaces, advisors and TargetSource are equal.
	 * <p>The compared object may be a JdkDynamicAopProxy instance itself
	 * or a dynamic proxy wrapping a JdkDynamicAopProxy instance.
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		if (other == this) {
			return true;
		}
		if (other == null) {
			return false;
		}

		JdkDynamicAopProxy otherProxy;
		if (other instanceof JdkDynamicAopProxy) {
			otherProxy = (JdkDynamicAopProxy) other;
		}
		else if (Proxy.isProxyClass(other.getClass())) {
			InvocationHandler ih = Proxy.getInvocationHandler(other);
			if (!(ih instanceof JdkDynamicAopProxy)) {
				return false;
			}
			otherProxy = (JdkDynamicAopProxy) ih;
		}
		else {
			// Not a valid comparison...
			return false;
		}

		// If we get here, otherProxy is the other AopProxy.
		return AopProxyUtils.equalsInProxy(this.advised, otherProxy.advised);
	}

	/**
	 * Proxy uses the hash code of the TargetSource.
	 */
	@Override
	public int hashCode() {
		return JdkDynamicAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
	}

}
