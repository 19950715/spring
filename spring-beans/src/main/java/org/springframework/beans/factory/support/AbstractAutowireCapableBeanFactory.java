/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.beans.factory.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 13.02.2004
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/**
	 * Whether this environment lives within a native image.
	 * Exposed as a private static field rather than in a {@code NativeImageDetector.inNativeImage()} static method due to https://github.com/oracle/graal/issues/2594.
	 * @see <a href="https://github.com/oracle/graal/blob/master/sdk/src/org.graalvm.nativeimage/src/org/graalvm/nativeimage/ImageInfo.java">ImageInfo.java</a>
	 */
	private static final boolean IN_NATIVE_IMAGE = (System.getProperty("org.graalvm.nativeimage.imagecode") != null);


	/** Strategy for creating bean instances. */
	private InstantiationStrategy instantiationStrategy;

	/** Resolver strategy for method parameter names. */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/** Whether to automatically try to resolve circular references between beans. */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to resort to injecting a raw bean instance in case of circular reference,
	 * even if the injected bean eventually got wrapped.
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/** Cache of candidate factory methods per factory class. */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		if (IN_NATIVE_IMAGE) {
			this.instantiationStrategy = new SimpleInstantiationStrategy();
		}
		else {
			this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
		}
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 */
	protected InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	protected ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
			AbstractAutowireCapableBeanFactory otherAutowireFactory =
					(AbstractAutowireCapableBeanFactory) otherFactory;
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use prototype bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition) {
			RootBeanDefinition rbd = (RootBeanDefinition) mbd;
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		}
		else {
			Object bean;
			if (System.getSecurityManager() != null) {
				bean = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(bd, null, this),
						getAccessControlContext());
			}
			else {
				bean = getInstantiationStrategy().instantiate(bd, null, this);
			}
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	//init-method调用之前回调所有BeanPostProcessor的postProcessBeforeInitialization方法
	//  也就是@PostConstruct注解标注的初始化方法，在applyMergedBeanDefinitionPostProcessors方法中已经解析了该注解
	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {
		//最终返回的结果
		Object result = existingBean;
		//遍历所有已注册的BeanPostProcessor，按照遍历顺序回调postProcessBeforeInitialization方法
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			//如果途中某个processor的postProcessBeforeInitialization方法返回null，那么不进行后续的回调
			//直接返回倒数第二个processor的postProcessBeforeInitialization方法的返回值
			if (current == null) {
				return result;
			}
			//改变result指向当前的返回值
			result = current;
		}
		//返回result
		return result;
	}

	// * @return 最后一个BeanPostProcessor的postProcessAfterInitialization方法的返回结果
	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {
		//用来保存返回的结果，默认为传递的existingBean
		Object result = existingBean;
		//遍历所有已注册的BeanPostProcessor，按照遍历顺序回调postProcessAfterInitialization方法
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			//按照遍历顺序调用postProcessAfterInitialization方法
			Object current = processor.postProcessAfterInitialization(result, beanName);
			//如果途中某个processor的postProcessAfterInitialization方法返回null，那么不进行后续的回调
			//直接返回倒数第二个processor的postProcessAfterInitialization方法的返回值
			//如果当前BeanPostProcessor后处理器的返回值为null，那么返回上一个不为null的结果
			if (current == null) {
				return result;
			}
			//改变result指向当前的返回值
			//更新要返回的结果
			result = current;
		}
		//遍历、处理完毕，返回最终的结果
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(
				existingBean, getBeanPostProcessorCache().destructionAware, getAccessControlContext()).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------
	/**
	 * 通过name解析bean，实际上还会匹配类型
	 */
	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			//调用了getBean方法，传递了两个参数name和type，实际上就是ResourceElement的name和lookupType属性
			//这表示找到的依赖需要满足这两个条件
			return getBean(name, descriptor.getDependencyType());
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 * @see #doCreateBean
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		//解析BeanDefinition中的class属性，并通过反射创建相应的Class
		//根据bean定义中的属性解析class，如果此前已经解析过了那么直接返回beanClass属性指定的Class对象，否则解析className字符串为Class对象
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		//如果已解析的class不为null，并且beanClass属性不为null，并且beanClass属性类型不是Class类型，即存的是全路径各类名字符串
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			//使用一个新的RootBeanDefinition克隆参数中的mbd
			mbdToUse = new RootBeanDefinition(mbd);
			//将mbdToUse的beanClass属性设置为解析出来的resolvedClass
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		try {
			//预先标记没有重载的方法
			//准备以及验证方法重写，也就是<lookup-method>、<replaced-method>这两个标签的配置，此前IoC学习的时候已经给介绍过了，一般用的不多
			//主要是校验指定名称的方法是否存在，如果不存在则抛出异常；以及设置是否存在重载方法，如果不存在重载方法，那么后续不需要参数校验。
			mbdToUse.prepareMethodOverrides();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			//第一次调用后置处理器 在bean实例化之前 aop  判断这个将要被实例化的bean要不要做代理 如果是切面类则不需要 会添加到advisedBeans map中
			//这里提供了一个机会，可以通过后处理器BeanPostProcessors创建并返回一个代理bean实例
			//Spring实例化bean之前的处理，给InstantiationAwareBeanPostProcessor后处理器一个返回代理对象来代替目标bean实例的机会
			//一般用不到，都是走Spring创建对象的逻辑
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			//如果bean不为null，那么就返回这个代理bean
			if (bean != null) {
				return bean;
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		try {
			//进这 实际开始创建bean
			//调用doCreateBean方法真正的创建bean实例
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		}
		catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// Instantiate the bean.
		//instanceWrapper表示一个bean的包装类对象
		BeanWrapper instanceWrapper = null;
		//如果mbd是单例的
		if (mbd.isSingleton()) {
			//尝试直接从factoryBeanInstanceCache中移除并获取正在创建中的FactoryBean的指定beanName的BeanWrapper
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		//如instanceWrapper果为null，一般都是走这个逻辑
		if (instanceWrapper == null) {
			//推断构造方法 并实例化对象，里面第二次调用后置处理器 默认就是通过反射来实例化一个bean的。
			/*
			 * 1 核心方法之一，使用适当的实例化策略创建Bean实例，并返回包装类BeanWrapper：
			 *   1 根据生产者Supplier实例化bean
			 *   2 根据工厂方法实例化bean
			 *   3 如果可以自动注入，则使用构造器自动注入实例化bean
			 *   4 不能自动注入，则使用默认无参构造器实例化bean
			 *
			 * 该方法结束，已经实例化bean完毕（可能进行了构造器依赖注入），后续就是其他依赖注入（setter方法、注解反射）的过程
			 */
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		//得到实例化出来的对象
		//返回包装的bean 实例
		Object bean = instanceWrapper.getWrappedInstance();
		//返回包装的 bean 实例的类型
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			//resolvedTargetType缓存设置为beanType
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		synchronized (mbd.postProcessingLock) {
			//如果没有应用过MergedBeanDefinitionPostProcessor
			if (!mbd.postProcessed) {
				try {
					//第三次调用后置处理器
					//通过后置处理器应用合并之后的bd  缓存了注入元素信息
					//解析合并之后的bd对象 把合并的bd里面的信息拿出来  看哪些属性需要自动注入 合并的bd来源于下面这行代码
					//org.springframework.beans.factory.support.DefaultListableBeanFactory.preInstantiateSingletons RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
					//找出所有需要完成注入的属性和方法 后面完成填充
					//为什么不需要找构造方法 因为前面已经推断了构造方法 然后调用了这个构造方法 完成了填充

					/*
					 * 2 核心方法之二
					 * 应用MergedBeanDefinitionPostProcessor类型后处理器的postProcessMergedBeanDefinition方法，允许后处理器修改已合并的 bean 定义
					 * 最重要的是它被用来查找、解析各种注解，常见的MergedBeanDefinitionPostProcessor的实现以及对应postProcessMergedBeanDefinition方法的功能有:
					 *  1 InitDestroyAnnotationBeanPostProcessor -> 处理方法上的@PostConstruct、@PreDestroy注解，用于初始化、销毁方法回调
					 *  2 CommonAnnotationBeanPostProcessor -> 处理字段和方法上的@WebServiceRef、@EJB、@Resource注解，用于资源注入
					 *  3 AutowiredAnnotationBeanPostProcessor ->  -> 处理字段和方法上的@Autowired、@Value、@Inject注解，用于自动注入
					 *  4 ApplicationListenerDetector -> 记录ApplicationListener类型的bean是否是单例的，后面监听器过滤的时候才会用到(不必关心)
					 *
					 * 这一步仅仅是查找、简单解析Class中的各种注解，并缓存起来，后面时机到了，自然会调用这些被缓存的注解，完成它们的功能
					 */
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				//标志位改为true
				mbd.postProcessed = true;
			}
		}

		/*
		 * 3 解决setter方法和注解反射注入的单例bean循环依赖问题
		 */

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		//如果当前bean的"singleton"单例，并且允许循环依赖（默认允许），并且正在创建当前单例bean实例
		//那么earlySingletonExposure设置为ture，表示允许早期单例实例的暴露，用于解决循环依赖
		//判断是否允许循环依赖
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		//如果允许早期单例暴露
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}
			//第四次调用后置处理器，判断是否需要aop，放了一个对象工厂进去
			/*
			 *
			 * 核心方法之三 addSingletonFactory：
			 *  将早期单例实例工厂存入singletonFactories缓存中，用于解决循环依赖
			 *  如果A依赖B、B依赖A，那么注入的时候，将会注入一个从singletonFactories中的ObjectFactory获取的早期对象
			 *  为什么叫"早期"呢？因为此时该实例虽然被创建，但是可能还没有执行后续的依赖注入（setter方法、注解反射）的过程，该bean实例是不完整的
			 *  但是此时能够保证获取的依赖不为null而不会抛出异常，并且和后续进行的注入对象是同一个对象，从而在不知不觉中解决循环依赖
			 *
			 * 以前的版本第二个参数传递的是一个ObjectFactory的匿名对象，Spring5以及Java8的之后第二个参数传递的是一个lambda对象
			 * lambda语法更加简单，采用invokedynamic指令运行时动态构建类，不会生成额外的class文件
			 *
			 * 这个lambda的意思就是，ObjectFactory对象的getObject方法实际上就是调用这里的getEarlyBeanReference方法
			 *
			 *  getEarlyBeanReference：
			 *      应用SmartInstantiationAwareBeanPostProcessor后处理器的getEarlyBeanReference方法
			 *      该方法可以改变要返回的提前暴露的单例bean引用对象，默认都是返回原值的，即不做改变，但是我们可以自己扩展。但是如果存在Spring AOP，则该方法可能会创建代理对象。
			 */
			addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
		}

		// Initialize the bean instance.
		/*
		 * 4 初始化 bean 实例
		 * 前面的createBeanInstance操作被称为"实例化"bean操作，简单的说就调用构造器创建对象
		 * 下面的操作就是"初始化"bean的操作，简单的说就是对bean实例进行依赖注入以及各种回调
		 */
		Object exposedObject = bean;
		try {
			//填充属性，也就是我们常说的自动注入
			//里面会完成第五次和第六次后置处理器的调用
			/*
			 * 核心方法之四 populateBean
			 *
			 * 填充(装配)bean实例，也就是setter方法和注解反射的属性注入过程
			 * 这里又有可能由于依赖其他bean而导致其他bean的初始化
			 */
			populateBean(beanName, mbd, instanceWrapper);
			//初始化spring
			//里面会进行第七次和第八次后置处理器的调用
			/**
			 * 当循环依赖并且有代理时，bean会在上面的getEarlyBeanReference方法完成代理。
			 * initializeBean方法执行完毕之后，获得的结果将会被赋给exposedObject实例，
			 * 注意，exposedObject最开始就是原始的bean实例。那么由于原始ClassA没有在initializeBean方法中被代理，
			 * 那么此时返回的exposedObject还是原始的bean实例。
			 */
			/*
			 * 核心方法之五 initializeBean
			 * 继续初始化bean实例，也就是回调各种方法
			 * 比如顺序回调所有设置的init初始化方法，以及回调Aware接口的方法，进行AOP代理等操作。
			 *
			 * initializeBean方法执行完毕之后，获得的结果将会被赋给exposedObject实例，
			 * 注意，exposedObject最开始就是原始的bean实例。那么由于如果原始ClassA没有在initializeBean方法中被代理，那么此时返回的exposedObject还是原始的bean实例。
			 */
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		}
		catch (Throwable ex) {
			if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
				throw (BeanCreationException) ex;
			}
			else {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
			}
		}
		/*
		 * 5 循环依赖检查，看是否需要抛出异常
		 */
		//如果允许暴露早期单例实例
		if (earlySingletonExposure) {
			/*
			 * 获取当前单例实例缓存，allowEarlyReference参数为false，因此只会尝试从singletonObjects和earlySingletonObjects中查找，没找到就返回null
			 *
			 * 如果不为null，说明存在循环依赖，此前另一个互相依赖的Bean通过getSingleton(beanName)获取当前bean实例时，
			 * 获取的结果就是ObjectFactory#getObject的返回值，实际上就是getEarlyBeanReference方法返回的结果，因此
			 * 最后还会将结果存入earlySingletonObjects缓存，因此这里获取的实际上就是另一个Bean注入的实例，可能是一个代理对象
			 */
			Object earlySingletonReference = getSingleton(beanName, false);
			/*
			 * 如果earlySingletonReference不为null，说明存在循环依赖，并且此前已经调用了ObjectFactory#getObject方法，即getEarlyBeanReference方法。
			 * getEarlyBeanReference方法中，会回调所有SmartInstantiationAwareBeanPostProcessor的getEarlyBeanReference方法
			 * 因此将可能进行基于AbstractAutoProxyCreator的Spring AOP代理增强，比如自定义Spring AOP代理、Spring声明式事务等
			 *
			 * 这里获取的earlySingletonReference可能就是一个代理对象
			 */
			if (earlySingletonReference != null) {
				/*
				 * 如果在经过populateBean和initializeBean方法之后返回的对象exposedObject还是等于原始对象bean，
				 * 即说明当前循环依赖的bean实例没有在populateBean和initializeBean这两个方法中调用的回调方法中被代理增强
				 * 或者只有基于AbstractAutoProxyCreator的Spring AOP代理增强，但是这在getEarlyBeanReference方法中已经被增强了
				 * Spring会保证基于AbstractAutoProxyCreator的增强只会进行一次，那么经过这两个填方法之后将仍然返回原始的bean
				 *
				 * 但是如果还有Spring @Async异步任务的AOP增强，由于它是通过AbstractAdvisingBeanPostProcessor来实现的，因此
				 * 经过这两个方法之后将返回一个新的代理的bean，这样exposedObject就和原来的bean不相等了
				 *
				 * 判断如果exposedObject和bean一致，也就是说经过了initializeBean返回的实例和最开始获取的实例是“同一个对象”，
				 * 那么说明，在初始化ClassA的时候没有进行AOP代理（如果进行了代理，一定会创建一个新的代理对象），
				 * 因此将exposedObject置为earlySingletonReference，也就是从缓存中获取的并且应用了getEarlyBeanReference方法的bean实例，
				 * 早期的ClassA实在方法中可能进行了代理也可能没有，因此最终需要返回earlySingletonReference这个对象。
				 */
				if (exposedObject == bean) {
					//那么exposedObject赋值为earlySingletonReference，对之前的循环引用没影响
					//这里的earlySingletonReference可能就是被基于AbstractAutoProxyCreator代理增强的代理对象
					exposedObject = earlySingletonReference;
				}
				//否则，说明该bean实例进行了其他的代理，比如Spring @Async异步任务
				//继续判断如果不允许注入原始bean实例，并且该beanName被其他bean依赖
				else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						/*
						 * 尝试将dependentBean对应的仅用于类型检查的已创建实例移除，如果是真正的创建的实例则不会一会，最后还会抛出异常
						 *
						 * 如果alreadyCreated缓存集合中不包含对应的dependentBean，说明该bean实例还未被真正创建，但是可能因为类型检查而创建
						 * 那么尝试移除对象缓存中的beanName对应的缓存（无论是否真正的因为类型检查而创建了），并返回true
						 * 如果alreadyCreated中包含对应的dependentBean否则，说明该bean实例因为其他用途已被创建了或者正在创建（比如真正的初始化该bean实例），
						 * 那么返回false，表示依赖该bean的bean实例可能已被创建了，但是注入的对象可能并不是最终的代理对象
						 * 因此后续将会抛出BeanCurrentlyInCreationException异常
						 *
						 * 在前面的doGetBean方法中，如果不是为了类型检查，那么会将即将获取的实例beanName通过markBeanAsCreated方法存入alreadyCreated缓存集合中
						 */
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							//移除失败的dependentBean加入actualDependentBeans集合中
							actualDependentBeans.add(dependentBean);
						}
					}
					/*
					 * 如果actualDependentBeans集合不为空，那么表示可能有其他依赖该bean的实例注入的并不是目前最终的bean实例，那么将抛出异常。
					 *
					 * 实际上对于普通bean以及通用的AOP循环依赖注入以及事务循环依赖，Spring都可以帮我们解决循环依赖而不会抛出异常！
					 * 如果对@Async注解标注的类进行setter方法和反射字段注解的循环依赖注入（包括自己注入自己），就会抛出该异常。
					 * 而@Async类抛出异常的根本原因这个AOP代理对象不是使用通用的AbstractAutoProxyCreator的方法创建的，
					 * 而是使用AsyncAnnotationBeanPostProcessor后处理器来创建的，可以加一个@Lazy注解解决！
					 */
					if (!actualDependentBeans.isEmpty()) {
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
								StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
								"] in its raw version as part of a circular reference, but has eventually been " +
								"wrapped. This means that said other beans do not use the final version of the " +
								"bean. This is often the result of over-eager type matching - consider using " +
								"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		try {
			//为bean注册DisposableBean
			/*
			 * 6 尝试将当前bean实例注册为可销毁的bean，即存入disposableBeans缓存中，后续容器销毁时将会进行销毁回调
			 * 注册销毁回调方法，可以来自于@PreDestroy注解、XML配置的destroy-method方法，以及DisposableBean接口，甚至AutoCloseable接口
			 */
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}
		//返回exposedObject，该实例被创建完毕
		return exposedObject;
	}

	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					return predicted;
				}
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType == null) {
			targetType = (mbd.getFactoryMethodName() != null ?
					getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
					resolveBeanClass(mbd, beanName, typesToMatch));
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		if (cachedReturnType != null) {
			return cachedReturnType.resolve();
		}

		Class<?> commonType = null;
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;

		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;

			String factoryBeanName = mbd.getFactoryBeanName();
			if (factoryBeanName != null) {
				if (factoryBeanName.equals(beanName)) {
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			}
			else {
				// Check declared factory method return type on bean class.
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}

			if (factoryClass == null) {
				return null;
			}
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			for (Method candidate : candidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							Class<?>[] paramTypes = candidate.getParameterTypes();
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							Object[] args = new Object[paramTypes.length];
							for (int i = 0; i < args.length; i++) {
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								if (valueHolder == null) {
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								if (valueHolder != null) {
									args[i] = valueHolder.getValue();
									usedValueHolders.add(valueHolder);
								}
							}
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								return null;
							}
						}
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					}
					else {
						uniqueCandidate = (commonType == null ? candidate : null);
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							return null;
						}
					}
				}
			}

			mbd.factoryMethodToIntrospect = uniqueCandidate;
			if (commonType == null) {
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		mbd.factoryMethodReturnType = cachedReturnType;
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
						((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
					factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
				}
				else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 * @param beanClass the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, a full creation of the FactoryBean is
	 * used as fallback (through delegation to the superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	@Deprecated
	@Nullable
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd the merged bean definition for the bean
	 * @param bean the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		//exposedObject赋值为参数bean对象
		Object exposedObject = bean;
		//如果当前bean定义不是合成的，并且具有InstantiationAwareBeanPostProcessor这个类型的后处理器
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			//获取、遍历全部注册的后处理器
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				//回调getEarlyBeanReference方法，传递的参数就是此前获取的bean实例以及beanName
				//该方法可以改变要返回的提前暴露的单例bean引用对象，默认直接返回参数bean实例
				//该方法中实际上就有可能对原始bean实例进行某些代理。
				//AbstractAutoProxyCreator后处理器则重写了该方法，用于对对早期bean引用创建并返回代理对象。
				// 因此，这个地方目前仅能对基于AbstractAutoProxyCreator体系创建代理对象的方式生成AOP代理对象，
				// 比如自定义AOP、Spring事务、Spring Cache等。如果是基于其他方式创建的代理对象，
				// 比如Spring异步任务就基于AbstractAdvisingBeanPostProcessor，那在这里就不会执行这些AOP增强的逻辑。
				exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
			}
		}
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean) {
				return (FactoryBean<?>) beanInstance;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			}
			catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			}
			catch (BeanCreationException ex) {
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			}
			finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		}
		catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		}
		catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		}
		finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * @param mbd the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		//获取、遍历全部已注册的后处理器
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			//回调postProcessMergedBeanDefinition方法，该方法可用于修改给定bean的已合并的RootBeanDefinition
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		//判断初始化之前有没有处理，有的话直接返回null
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			// 确保mbd不是合成的，并且具有InstantiationAwareBeanPostProcessor这个后处理器
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				//获取这个bean的目标class
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					//应用InstantiationAwareBeanPostProcessor后处理器的postProcessBeforeInstantiation方法
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					//如果bean不为null
					if (bean != null) {
						//那么继续应用所有已注册的后处理器的postProcessAfterInitialization方法
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			//如果bean不为空，则将RootBeanDefinition的beforeInstantiationResolved属性赋值为true，表示在Spring实例化之前已经解析
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * @param beanClass the class of the bean to be instantiated
	 * @param beanName the name of the bean
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		//遍历全部bean后处理器         //找到类型为InstantiationAwareBeanPostProcessor的后处理器进行回调
		for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
			Object result = bp.postProcessBeforeInstantiation(beanClass, beanName);
			//如果结果不为null，表示创建了代理对象，那么直接返回该对象
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/**
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a BeanWrapper for the new instance
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 * * 使用适当的实例化策略为指定的 bean 创建新实例
	 *  * 采用的策略有：工厂方法实例化、自动注入带参数构造器实例化或默认无参构造器简单实例化。
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		//这列再次解析BeanDefinition中的class,确保现在bean的Class己经被解析好了
		/*
		 * 根据bean定义中的属性解析class，如果此前已经解析过了那么直接返回beanClass属性指定的Class对象，否则解析className字符串为Class对象
		 */
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		//如果bean的类不是public修饰的，并且没有通过反射设置允许访问的权限
		//此时当然就不能通过反射创建bean了，此时就会提前抛出异常
		//如果beanClass不为null，并且该类的访问权限修饰符不是public的，并且不允许访问非公共构造器和方法(默认是允许的)
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		//bd.setInstanceSupplier(()->new OrderService(beanFactory.getBean(UserService.class))
		//设置Supplier 相当于指定UserService构造方法参数 也就不需要走下面的推断构造方法
		//获取用于创建 bean 实例的回调生产者。这是Spring5以及Java8的新特性
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		/*
		 * 1 如果存在bean实例生产者
		 */
		if (instanceSupplier != null) {
			//那么从给定的生产者获取 bean 实例
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		/**
		 * 如果是appconifg里面的@Bean方法  会有FactoryMethod
		 * //如果程序员自己提供了工厂方法自己去实例化，就不需要走下面的推断构造方法，推断构造方法是为了spring自己去实例化对象
		 */
		/*
		 * 2 如果存在工厂方法，即XML的factory-method属性
		 */
		if (mbd.getFactoryMethodName() != null) {
			//那么从工厂方法中获取 bean 实例
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// Shortcut when re-creating the same bean...
		//表示创建对象的构造方法没有被解析过
		/*
		 * 3 Spring会将此前已经解析、确定好的构造器缓存到resolvedConstructorOrFactoryMethod中
		 * 后续再次创建对象时首先尝试从缓存中查找已经解析过的构造器或者工厂方法，避免再次创建相同bean时再次解析
		 *
		 * 对于原型（prototype）bean的创建来说，这非常的有用，可以快速的创建对象，称为Shortcut
		 */
		//resolved表示是否已解析构造器或者工厂方法的标志，默认false
		boolean resolved = false;
		//resolved表示是否构造器自动注入的标志，默认false
		boolean autowireNecessary = false;
		//如果没有指定外部参数
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				//表示已经找到了创建对象的方式  一般用于原型  原型在第二次创建同一个bean时这里会不等于null
				//如果解析的构造器或工厂方法缓存不为null，表示已经解析过
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					//resolved标志位设置为true
					resolved = true;
					//autowireNecessary标志位设置为constructorArgumentsResolved
					//constructorArgumentsResolved属性表示是否已经解析了构造器参数，如果已经解析了，则需要通过缓存的构造器来实例化
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		//如果已经解析过，则使用解析过的构造器进行初始化，设置的显示参数此时无效
		if (resolved) {
			//如果支持自动注入
			if (autowireNecessary) {
				//使用构造器自动注入初始化
				return autowireConstructor(beanName, mbd, null, null);
			}
			else {
				//使用默认无参构造器初始化
				return instantiateBean(beanName, mbd);
			}
		}

		// Candidate constructors for autowiring?
		//第二次调用后置处理器推断构造方法  自动装配和手动装配都会进这里面并且逻辑一样 一般返回null 或者只返回一个
		//通过构造注入一个然后完成实例化对象
		/*
		 * 4 一般bean第一次解析都是走下面的逻辑，使用构造器初始化
		 */

		/*
		 * 利用SmartInstantiationAwareBeanPostProcessor后处理器回调，自动匹配、推测需要使用的候选构造器数组ctors
		 * 这里的是解析注解，比如@Autowired注解，因此需要开启注解支持
		 */
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		/*
		 * 5 如果ctors的候选构造器数组不为null（这是查找注解，比如@Autowired注解），
		 * 或者自动注入模式为构造器自动注入（这是XML设置的自动注入模式）
		 * 或者XML定义了<constructor-arg/>标签，或者外部设置的参数args不为空。
		 *
		 * 四个条件满足一个，那么使用构造器自动注入
		 */
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			//那么使用构造器自动注入
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		//通过无参构造方法实例化对象
		/*
		 * 6 没有特殊处理，使用默认无参构造器初始化。
		 * 在大量使用注解的今天，一般都是通过无参构造器创建对象的，还能避免构造器的循环依赖
		 * 当然无参构造器一般也是在上面的autowireConstructor方法中调用的
		 */
		return instantiateBean(beanName, mbd);
	}

	/**
	 * Obtain a bean instance from the given supplier.
	 * @param instanceSupplier the configured supplier
	 * @param beanName the corresponding bean name
	 * @return a BeanWrapper for the new instance
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
		Object instance;
		//获取当前线程正在创建的 bean 的名称支持obtainFromSupplier方法加入的属性
		String outerBean = this.currentlyCreatedBean.get();
		//设置为给定的beanName，表示正在创建当前beanName的实例
		this.currentlyCreatedBean.set(beanName);
		try {
			//从生产者获取实例
			instance = instanceSupplier.get();
		}
		finally {
			//如果outerBean不为null
			if (outerBean != null) {
				//那么还是设置为outerBean，表示正在创建outerBean的实例
				this.currentlyCreatedBean.set(outerBean);
			}
			//否则，移除当前currentlyCreatedBean的key，表示没有使用Supplier创建
			else {
				this.currentlyCreatedBean.remove();
			}
		}
		//如果生产者返回null，那么返回NullBean包装bean
		if (instance == null) {
			instance = new NullBean();
		}
		BeanWrapper bw = new BeanWrapperImpl(instance);
		//初始化BeanWrapper
		initBeanWrapper(bw);
		return bw;
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * @since 5.0
	 * @see #obtainFromSupplier
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {
		//获取当前线程正在创建的bean的名称，主要是支持obtainFromSupplier方法，一般情况获取到的值都为null
		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}
		//如果为null，一般走这一步逻辑，调用父类AbstractBeanFactory的同名方法
		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * @param beanClass the raw class of the bean
	 * @param beanName the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	/**
	 * 通过检查所有已注册的SmartInstantiationAwareBeanPostProcessor后处理器，确定要用于给定 bean 的候选构造器
	 * 具体的检查逻辑是在SmartInstantiationAwareBeanPostProcessor后处理器的determineCandidateConstructors方法中
	 * <p>
	 * 注意这里是检查注解，比如@Autowired注解，因此需要开启注解支持，比如annotation-config或者component-scan
	 * 该类型的后置处理器的实现有两个：ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor，AutowiredAnnotationBeanPostProcessor
	 * ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor中什么也没干，因此具体的逻辑都在AutowiredAnnotationBeanPostProcessor中
	 * <p>
	 * 所以说这个后置处理器的determineCandidateConstructors方法执行时机是在: 对象实例化之前执行
	 *
	 * @param beanClass beanClass
	 * @param beanName  beanName
	 * @return 候选构造器数组，如果没有指定则为null
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {
		//如果beanClass不为null，并且具有InstantiationAwareBeanPostProcessor这个类型的后处理器
		//SmartInstantiationAwareBeanPostProcessor继承了InstantiationAwareBeanPostProcessor
		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			//遍历所有注册的BeanPostProcessor后处理器
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				//如果属于SmartInstantiationAwareBeanPostProcessor类型
				//回调determineCandidateConstructors方法，返回候选构造器数组
				//比如使用@Autowired注解标注的构造器都被当作候选构造器，加入到数组中，留待后续继续筛选
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				//如果返回值不为null，那么直接返回结果，表明解析到了候选构造器
				if (ctors != null) {
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			Object beanInstance;
			//如果存在安全管理器，一般不存在
			if (System.getSecurityManager() != null) {
				beanInstance = AccessController.doPrivileged(
						(PrivilegedAction<Object>) () -> getInstantiationStrategy().instantiate(mbd, beanName, this),
						getAccessControlContext());
			}
			else {
				//实例化bean

				//通用逻辑
				//getInstantiationStrategy，返回用于创建 bean 实例的实例化策略，就是instantiationStrategy属性
				//默认是CglibSubclassingInstantiationStrategy类型的实例，实现了SimpleInstantiationStrategy
				beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			}
			//然后将实例化好bean,封装到BeanWrapper中并返回
			//新建BeanWrapperImpl，设置到内部属性中
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			//初始化BeanWrapper，此前讲过了
			//主要是为当前的BeanWrapperImpl实例设置转换服务ConversionService以及注册自定义的属性编辑器PropertyEditor。
			initBeanWrapper(bw);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param ctors the chosen candidate constructors
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 *  * 选择合适的构造器实例化bean，并且进行构造器自动注入，返回被包装后的bean实例——BeanWrapper对象。
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {
		//内部实际上是委托的ConstructorResolver构造器解析器的autowireConstructor方法来实现的
		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * @param beanName the name of the bean
	 * @param mbd the bean definition for the bean
	 * @param bw the BeanWrapper with bean instance
	 *           使用 bean 定义中的属性值在给定的 BeanWrapper 中填充 bean 实例，简单的说就是：
	 *  		setter方法和注解反射方式的依赖注入，有可能由于依赖其他bean而导致其他bean的初始化
	 */
	@SuppressWarnings("deprecation")  // for postProcessPropertyValues
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		/*
		 * 1 校验bw为null的情况
		 * 如果此bean定义中定义了<property>标签，那么抛出异常，其他情况则直接返回
		 */
		//如果bw为null
		if (bw == null) {
			//如果mbd存在propertyValues属性，即定义了<property>标签
			//因为BeanWrapper都为null了，不能进行依赖注入，那么抛出异常
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// Skip property population phase for null instance.
				//跳过null实例的属性装配阶段
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		//看是否需要完成属性填充
		//判断是否需要完成属性注入，如果不需要就改变continuewithPropertyPopulation=false
		//如果程序员没有去扩展spring那么continuewithPropertyPopulation为true
		//在bean属性值设置之前，提供了一个机会去修bean的状态

		/*
		 * 2 在bean实例化之后，属性填充（初始化）之前，回调InstantiationAwareBeanPostProcessor后处理器的
		 * postProcessAfterInstantiation方法，可用于修改bean实例的状态
		 *
		 * Spring在这个扩展点方法中没有任何额外操作，但是我们可以自定义后处理器，重写该方法定义自己的逻辑。
		 */
		// 确保mbd不是合成的，并且具有InstantiationAwareBeanPostProcessor这个后处理器
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			//遍历所有注册的BeanPostProcessor后处理器
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				//回调postProcessAfterInstantiation方法，传递的参数就是此前获取的bean实例以及beanName
				//该方法可用于在bean实例化之后，初始化之前，修改bean实例的状态
				//如果返回false，则不会继续应用后续的处理同时也会结束后续的属性填充流程，该方法结束，否则继续向后调用
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					return;
				}
			}
		}

		//获取BeanDefinition中的所有属性信息
		//获取bean定义的PropertyValues集合，在此前的parsePropertyElement方法中，我们说过
		//所有的<property>标签标签都被解析为PropertyValue对象并存入PropertyValues集合中了
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

		//判断是否自动注入
		/*
		 * 3 根据名称或者类型进行setter自动注入，适用于基于XML的配置autowire自动注入，现在很少基于XML配置了
		 * 并没有真正的注入，而是将可以自动注入的属性名和bean实例存入新建的newPvs中，后面会统一注入
		 */
		//获取已解析的自动注入模式，默认就是0，即不自动注入，可以设置，对应XML的autowire属性
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		//如果是1（就是byName），或者如果是2（就是byType），单纯使用注解注入就是0
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			//根据原来的属性新建一个属性集合
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			// 基于byName的setter自动注入
			//并没有真正的注入，而是将可以自动注入的属性名和bean实例存入新建的newPvs中，后面会统一注入
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			// 基于byType的setter自动注入
			//并没有真正的注入，而是将可以自动注入的属性名和bean实例存入新建的newPvs中，后面会统一注入
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				//找出bw当中所有的需要注入的set方法
				//方法包含了一个参数-----就会把这个参数的值找出来
				autowireByType(beanName, mbd, bw, newPvs);
			}
			//pvs赋值为newPvs
			//现在pvs中包括了我们定义的<property>标签属性，以及找到的byName和byType的setter自动注入属性
			pvs = newPvs;
		}

		//检查Spring容器中，是否注册过后处理器InstantiationAwareBeanPostProcessor
		//是否具有InstantiationAwareBeanPostProcessor这个后处理器
		boolean hasInstAwareBpps = hasInstantiationAwareBeanPostProcessors();
		//检查是否需要对属性进行引用检查
		//是否需要进行依赖检查，即是否设置dependencyCheck属性，表示属性强制检查，就是XML的dependency-check属性
		//然而这个属性早在spring3.0的时候就被废弃了，代替它的就是构造器注入或者@Required，默认就是0，不进行强制检查，因此为false
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

		//封装bean中，每个属性信息的数组
		PropertyDescriptor[] filteredPds = null;
		/*
		 * 4 查找全部InstantiationAwareBeanPostProcessor后处理器，回调postProcessProperties方法
		 * 解析此前通过applyMergedBeanDefinitionPostProcessors方法找到的自动注入注解，这里进行了注解的真正的注入
		 */
		if (hasInstAwareBpps) {
			//如果为null，初始化一个空的MutablePropertyValues对象
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			//调用后置处理器完成注解的属性填充
			//获取、遍历全部注册的后处理器
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				//完成属性填充 执行后bw的wrappedObject就有对应的属性了 执行前为null
				/*
				 * 回调postProcessProperties方法，传递的参数就是目前的pvs、bean实例、beanName，解析、注入此前通过
				 * applyMergedBeanDefinitionPostProcessors方法找到的自动注入注解，返回PropertyValues
				 *
				 * CommonAnnotationBeanPostProcessor -> 解析注入@WebServiceRef、@EJB、@Resource注解，默认直接返回参数pvs
				 * AutowiredAnnotationBeanPostProcessor -> 解析注入@Autowired、@Value、@Inject注解，默认直接返回参数pvs
				 *
				 * 在Spring 5.1 之前使用的是postProcessPropertyValues回调方法，Spring 5.1开始该方法被不推荐使用，推荐使用postProcessProperties来替代
				 */
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				//如果pvsToUse为null，那么调用此前的已被放弃的postProcessPropertyValues方法继续尝试
				if (pvsToUse == null) {
					//获取属性描述符
					if (filteredPds == null) {
						filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
					}
					//调用postProcessPropertyValues方法，该方法已被丢弃
					//实际上CommonAnnotationBeanPostProcessor和AutowiredAnnotationBeanPostProcessor的方法内部就是直接调用的postProcessProperties方法
					//只有RequiredAnnotationBeanPostProcessor有自己的逻辑，但是我们知道RequiredAnnotationBeanPostProcessor已经整体丢弃了
					pvsToUse = bp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
					//还是返回null，那什么直接结束这个方法
					if (pvsToUse == null) {
						return;
					}
				}
				//从新设置pvs，对于CommonAnnotationBeanPostProcessor和AutowiredAnnotationBeanPostProcessor来说具有同一批数据
				pvs = pvsToUse;
			}
		}
		//执行完上门的for循环后 属性完成注入
		//如果需要进行依赖检查，默认不需要，Spring3.0之后没法设置设置
		if (needsDepCheck) {
			if (filteredPds == null) {
				filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			}
			//属性依赖的检查
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}
		/*
		 * 5 如果pvs不为null，这里默认的pvs就是<property>标签和byName或者byType找到的属性值的汇总
		 * 这里将所有PropertyValues中的属性继续填充到bean实例中
		 */
		if (pvs != null) {
			//把程序员手动提供的或者spring找出来的属性值应用上
			// spring什么情况会去找-----自动注入的情况下
			//将收集到的属性信息，统一设置hean实例中
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 * @param mbd bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 *            通过"byName"引用此工厂中的其他 bean 实例填充缺失的属性，将属性名和bean实例存入newPvs集合中
	 *  		并没有真正的注入依赖，类似于预先查找
	 */
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
		//获取需要自动依赖注入的"属性名"数组，实际上是查找的setter方法
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		//遍历属性名数组
		for (String propertyName : propertyNames) {
			/*
			 * 如果此 Bean 工厂及其父工厂包含具有给定名称的 bean 定义或bean实例
			 */
			if (containsBean(propertyName)) {
				//调用getBean通过propertyName获取bean实例，这里就有可能初始化该beanName的实例
				Object bean = getBean(propertyName);
				//将propertyName和bean实例加入到pvs集合中
				pvs.add(propertyName, bean);
				//那么将propertyName和beanName的依赖关系注册到dependentBeanMap和dependenciesForBeanMap缓存中
				//表示beanName的实例依赖propertyName的实例，这个方法我们在前面就讲过了
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			}
			/*
			 * 如果不包含，那么不进行注入
			 */
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * @param beanName the name of the bean to autowire by type
	 * @param mbd the merged bean definition to update through autowiring
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 * @param pvs the PropertyValues to register wired objects with
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}

		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		//找出set方法的名称  比如setName  这里就为name
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			try {
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is a unsatisfied, non-simple property.
				if (Object.class != pd.getPropertyType()) {
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					//找出set方法的参数Y
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					if (autowiredArgument != null) {
						pvs.add(propertyName, autowiredArgument);
					}
					for (String autowiredBeanName : autowiredBeanNames) {
						//注册bean的依赖
						registerDependentBean(autowiredBeanName, beanName);
						if (logger.isTraceEnabled()) {
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					autowiredBeanNames.clear();
				}
			}
			catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * @param mbd the merged bean definition the bean was created with
	 * @param bw the BeanWrapper the bean was created with
	 * @return an array of bean property names
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		PropertyValues pvs = mbd.getPropertyValues();
		//这里拿出两个 一个是class  另一个是wsy属性
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		//这里过滤后只会返回wsy  会过滤get方法
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				result.add(pd.getName());
			}
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				if (existing != null) {
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * @param bw the BeanWrapper the bean was created with
	 * @return the filtered PropertyDescriptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		pds.removeIf(this::isExcludedFromDependencyCheck);
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * @param pd the PropertyDescriptor of the bean property
	 * @return whether the bean property is excluded
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition the bean was created with
	 * @param pds the relevant property descriptors for the target bean
	 * @param pvs the property values to be applied to the bean
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {

		int dependencyCheck = mbd.getDependencyCheck();
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				if (unsatisfied) {
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * @param beanName the bean name passed for better exception information
	 * @param mbd the merged bean definition
	 * @param bw the BeanWrapper wrapping the target object
	 * @param pvs the new property values
	 *            应用从<property>标签和byName或者byType找到的属性值，在此前已经应用过某些注解注入了
	 *  * 这个方法可以说是为基于XML的setter属性注入准备的，纯粹的注解注入在前一步postProcessProperties已经完成了
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		//如果是属性值集合为空，直接返回
		if (pvs.isEmpty()) {
			return;
		}
		//安全管理器相关，不考虑
		if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
			((BeanWrapperImpl) bw).setSecurityContext(getAccessControlContext());
		}

		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;
		//如果属于MutablePropertyValues，一般都是这个类型
		if (pvs instanceof MutablePropertyValues) {
			mpvs = (MutablePropertyValues) pvs;
			//如果已转换类型，这个第一次创建bean的时候一般是false，后续可能为true
			//第一次解析之后会存储起来，后续走这个判断，直接使用"捷径"
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					//已转换类型，通过bw调用setPropertyValues注入属性值，完事儿直接返回
					bw.setPropertyValues(mpvs);
					return;
				}
				catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			//如果没转换，那么获取内部的原始PropertyValue属性值集合
			original = mpvs.getPropertyValueList();
		}
		else {
			//如果不是MutablePropertyValues类型，那么原始属性值数组转换为集合
			original = Arrays.asList(pvs.getPropertyValues());
		}

		/*
		 * 获取AbstractBeanFactory中的typeConverter自定义类型转换器属性，该属性用来覆盖默认属性编辑器PropertyEditor
		 * 并没有提供getCustomTypeConverter方法的默认返回，因此customConverter默认返回null，我们同样可以自己扩展
		 * 如果自定义类型转换器customConverter不为null，那么就使用customConverter
		 * 否则使用bw对象本身，因为BeanWrapper也能实现类型转换，一般都是使用bw本身
		 */
		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		//初始化一个valueResolver对象，用于将 bean 定义对象中包含的值解析为应用于目标 bean 实例的实际值
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		// 创建深克隆副本，存放解析后的属性值
		List<PropertyValue> deepCopy = new ArrayList<>(original.size());
		//resolveNecessary表示是否需要解析，默认为false
		boolean resolveNecessary = false;
		//遍历原始值属性集合的每一个属性，转换类型
		for (PropertyValue pv : original) {
			/*
			 * 如果单个属性已解析，直接加入解析值集合中
			 */
			if (pv.isConverted()) {
				deepCopy.add(pv);
			}
			/*
			 * 如果没有转换过，那么需要转换
			 */
			else {
				//获取属性名
				String propertyName = pv.getName();
				//获取属性值
				Object originalValue = pv.getValue();
				//一般都不是AutowiredPropertyMarker实例
				if (originalValue == AutowiredPropertyMarker.INSTANCE) {
					Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
					if (writeMethod == null) {
						throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
					}
					originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
				}
				/*
				 * 第一次转换
				 * 这里的resolveValueIfNecessary方法，则是将之前的值包装类转换为对应的实例Java类型，比如ManagedArray转换为数组、ManagedList转换为list集合
				 * 如果是引用其他bean或者指定了另一个bean定义，比如RuntimeBeanReference，则在这里会先初始化该引用的bean实例并返回
				 * 相当于反解回来，这里的resolvedValue就是转换后的实际Java类型，这就是所谓的转换，这也是前面所说的运行时解析，就是这个逻辑
				 *
				 * 之前就讲过了，在resolveConstructorArguments方法解析构造器参数的时候也用的是这个方法转换类型
				 */
				//第一次转换的值
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				//第二次转换的值
				Object convertedValue = resolvedValue;
				//是否可以转换
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				//第二次转换
				//这里才是尝试将已解析的属性值继续转换为setter方法参数类型的逻辑，在上面的resolveValueIfNecessary中仅仅是将值得包装对象解析为Java值类型而已
				if (convertible) {
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				/*
				 * 在合并的BeanDefinition中存储转换后的值，以避免为每个创建的bean实例重新转换
				 */
				//如果原值等于转换后的值，原值一般都是RuntimeBeanReference、ManagedArray、TypedStringValue等包包装类一般都不相等，
				if (resolvedValue == originalValue) {
					//设置当前PropertyValue的converted属性为true，convertedValue属性为当前已转换的convertedValue值
					if (convertible) {
						pv.setConvertedValue(convertedValue);
					}
					//加入解析值集合中
					deepCopy.add(pv);
				}
				//如果属性支持转换，并且原始值属于String，并且原始值是非动态的，并且转换后的值不是Collection或者数组
				else if (convertible && originalValue instanceof TypedStringValue &&
						!((TypedStringValue) originalValue).isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					//设置当前PropertyValue的converted属性为true，convertedValue属性为当前已转换的convertedValue值
					pv.setConvertedValue(convertedValue);
					//加入解析值集合中
					deepCopy.add(pv);
				}
				else {
					//resolveNecessary设置为true，表示还需要继续解析
					resolveNecessary = true;
					//根据新值创建一个PropertyValue加入解析值集合中
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		//如果是MutablePropertyValues类型，并且不需要继续解析了
		if (mpvs != null && !resolveNecessary) {
			//那么当前MutablePropertyValues的converted标记为true
			mpvs.setConverted();
		}

		/*
		 * 根据转换之后的属性值deepCopy新建一个MutablePropertyValues，通过bw调用setPropertyValues注入属性值
		 * 这个方法的调用链比较长，后续解析
		 */
		// Set our (possibly massaged) deep copy.
		try {
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		}
		catch (BeansException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

		if (converter instanceof BeanWrapperImpl) {
			return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
		}
		else {
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 * * 初始化给定的 bean 实例，主要是应用各种回调方法：
	 *  * 1 回调一些特殊的Aware接口的方法，包括BeanNameAware、BeanClassLoaderAware、BeanFactoryAware
	 *  * 2 回调所有BeanPostProcessor的postProcessBeforeInitialization方法，包括@PostConstruct注解标注的初始化方法
	 *  * 3 回调所有配置的init-method方法，包括InitializingBean.afterPropertiesSet()和init-method
	 *  * 4 回调所有BeanPostProcessor的postProcessAfterInitialization方法
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		/*
		 * 1 一些特殊的Aware接口的回调，BeanNameAware、BeanClassLoaderAware、BeanFactoryAware
		 */
		//如果存在安全管理器，一般不存在
		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				invokeAwareMethods(beanName, bean);
				return null;
			}, getAccessControlContext());
		}
		else {
			//调用感知接口中的方法
			//回调
			invokeAwareMethods(beanName, bean);
		}
		/*
		 * 2 initMethod调用之前回调所有BeanPostProcessor的postProcessBeforeInitialization方法
		 *
		 * 其中就包括@PostConstruct注解标注的初始化方法的调用，在applyMergedBeanDefinitionPostProcessors方法中已经解析了该注解
		 */
		Object wrappedBean = bean;
		//如果mbd为null或者不是合成的，一般都不是
		if (mbd == null || !mbd.isSynthetic()) {
			//执行spring当中的内置处理器  执行生命周期的初始化回调 执行@PostConstruct
			//bean初始化前，通过后置处理器调整下bean的实例
			//回调
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}
		/*
		 * 3 回调所有配置的initMethod初始化方法
		 * 包括InitializingBean.afterPropertiesSet()，以及XML配置的init-method属性指定的方法
		 */
		try {
			//执行InitializingBean初始化  执行生命周期的初始化回调 跟上面一样
			//执行bean的初始化方法
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null),
					beanName, "Invocation of init method failed", ex);
		}
		/*
		 * 4 initMethod调用之后回调所有BeanPostProcessor的postProcessAfterInitialization方法
		 */
		if (mbd == null || !mbd.isSynthetic()) {
			//改变bean返回代理对象 完成代理
			//bean初始化后，通过后置处理器调整下bean的实例
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}
		//返回最终的结果
		return wrappedBean;
	}

	/**
	 *  一些特殊的Aware接口的回调，顺序为（如果存在）：
	 *  4. 1 BeanNameAware.setBeanName(name)
	 *  5. 2 BeanClassLoaderAware.setBeanClassLoader(classLoader)
	 *  6. 3 BeanFactoryAware.setBeanFactory(beanFactory)
	 *   8. 这些Aware接口在此前的createBeanFactory方法中已经被加入到了忽略setter方法的自动装配的集合ignoredDependencyInterfaces中
	 * @param beanName
	 * @param bean
	 */
	private void invokeAwareMethods(String beanName, Object bean) {
		/*如果属于Aware接口*/
		if (bean instanceof Aware) {
			/*
			 * 1 如果属于BeanNameAware，那么回调setBeanName方法，将beanName作为参数
			 */
			if (bean instanceof BeanNameAware) {
				((BeanNameAware) bean).setBeanName(beanName);
			}
			/*
			 * 2 如果属于BeanClassLoaderAware，那么回调setBeanClassLoader方法，将ClassLoader作为参数
			 */
			if (bean instanceof BeanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
				}
			}
			/*
			 * 3 如果属于BeanFactoryAware，那么回调setBeanFactory方法，将当前beanFactory作为参数
			 */
			if (bean instanceof BeanFactoryAware) {
				((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean the new bean instance we may need to initialize
	 * @param mbd the merged bean definition that the bean was created with
	 * (can also be {@code null}, if given an existing bean instance)
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 *  回调自定义的initMethod初始化方法，顺序为：
	 *  * 1 InitializingBean.afterPropertiesSet()方法
	 *  * 2 XML配置的init-method方法
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		//方法invokeInitMethods首先会判断当前bean，是否是接口InitializingBean的实例，如果是的话，就会执行InitializingBean中的方法afterPropertiesSet。
		//bean实例是否属于InitializingBean
		boolean isInitializingBean = (bean instanceof InitializingBean);
		/*
		 * 1 如果属于InitializingBean
		 * 2 并且externallyManagedInitMethods集合中不存在afterPropertiesSet方法
		 *   前面的在LifecycleMetadata的checkedInitMethods方法中我们就知道，通过@PostConstruct标注的方法会被存入externallyManagedInitMethods中
		 *   如果此前@PostConstruct注解标注了afterPropertiesSet方法，那么这个方法不会被再次调用，这就是externallyManagedInitMethods防止重复调用的逻辑
		 */
		if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			if (System.getSecurityManager() != null) {
				try {
					AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
						((InitializingBean) bean).afterPropertiesSet();
						return null;
					}, getAccessControlContext());
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				//执行InitializingBean中的方法afterPropertiesSet  主要是对bean实例进行一些配置和最终的初始化操作
				//回调afterPropertiesSet方法
				((InitializingBean) bean).afterPropertiesSet();
			}
		}

		if (mbd != null && bean.getClass() != NullBean.class) {
			//获取initMethodName属性，就是XML的init-method属性
			String initMethodName = mbd.getInitMethodName();
			/*
			 * 1 如果存在init-method方法
			 * 2 并且不是InitializingBean类型或者是InitializingBean类型但是initMethodName不是"afterPropertiesSet"方法
			 *   这里也是防止重复调用同一个方法的逻辑，因为在上面会调用afterPropertiesSet方法，这里不必再次调用
			 * 3 并且externallyManagedInitMethods集合中不存在该方法
			 *   在LifecycleMetadata的checkedInitMethods方法中我们就知道，通过@PostConstruct标注的方法会被存入externallyManagedInitMethods中
			 *   如果此前@PostConstruct注解标注了afterPropertiesSet方法，那么这个方法不会被再次调用，这就是externallyManagedInitMethods防止重复调用的逻辑
			 */
			if (StringUtils.hasLength(initMethodName) &&
					!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
					!mbd.isExternallyManagedInitMethod(initMethodName)) {
				//执行自定义的初始化方法
				//反射回调init-method的方法
				invokeCustomInitMethod(beanName, bean, mbd);
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd)
			throws Throwable {

		String initMethodName = mbd.getInitMethodName();
		Assert.state(initMethodName != null, "No init method set");
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

		if (initMethod == null) {
			if (mbd.isEnforceInitMethod()) {
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				return;
			}
		}

		if (logger.isTraceEnabled()) {
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod);

		if (System.getSecurityManager() != null) {
			AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
				ReflectionUtils.makeAccessible(methodToInvoke);
				return null;
			});
			try {
				AccessController.doPrivileged((PrivilegedExceptionAction<Object>)
						() -> methodToInvoke.invoke(bean), getAccessControlContext());
			}
			catch (PrivilegedActionException pae) {
				InvocationTargetException ex = (InvocationTargetException) pae.getException();
				throw ex.getTargetException();
			}
		}
		else {
			try {
				ReflectionUtils.makeAccessible(methodToInvoke);
				//通过反射，让bean来执行初始化方法
				methodToInvoke.invoke(bean);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				}
				else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
