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

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.inject.Provider;

import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CompositeIterator;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses:
 * see for example {@link PropertiesBeanDefinitionReader} and
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 16 April 2001
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	@Nullable
	private static Class<?> javaxInjectProviderClass;

	static {
		try {
			javaxInjectProviderClass =
					ClassUtils.forName("javax.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			javaxInjectProviderClass = null;
		}
	}


	/** Map from serialized id to factory instance. */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/** Optional id for this factory, for serialization purposes. */
	@Nullable
	private String serializationId;

	/** Whether to allow re-registration of a different definition with the same name. */
	private boolean allowBeanDefinitionOverriding = true;

	/** Whether to allow eager class loading even for lazy-init beans. */
	private boolean allowEagerClassLoading = true;

	/** Optional OrderComparator for dependency Lists and arrays. */
	@Nullable
	private Comparator<Object> dependencyComparator;

	/** Resolver to use for checking if a bean definition is an autowire candidate. */
	private AutowireCandidateResolver autowireCandidateResolver = SimpleAutowireCandidateResolver.INSTANCE;

	/** Map from dependency type to corresponding autowired value. */
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/** Map of bean definition objects, keyed by bean name. */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/** Map from bean name to merged BeanDefinitionHolder. */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/** Map of singleton and non-singleton bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/** Map of singleton-only bean names, keyed by dependency type. */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/** List of bean definition names, in registration order. */
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/** List of names of manually registered singletons, in registration order. */
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/** Cached array of bean definition names in case of frozen configuration. */
	@Nullable
	private volatile String[] frozenBeanDefinitionNames;

	/** Whether bean definition metadata may be cached for all beans. */
	private volatile boolean configurationFrozen;


	/**
	 * Create a new DefaultListableBeanFactory.
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 */
	public void setSerializationId(@Nullable String serializationId) {
		if (serializationId != null) {
			serializableFactories.put(serializationId, new WeakReference<>(this));
		}
		else if (this.serializationId != null) {
			serializableFactories.remove(this.serializationId);
		}
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 * @since 4.1.2
	 */
	@Nullable
	public String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 * @since 4.0
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}.
	 * @since 4.0
	 */
	@Nullable
	public Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware) {
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
					return null;
				}, getAccessControlContext());
			}
			else {
				((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
			}
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory) {
			DefaultListableBeanFactory otherListableFactory = (DefaultListableBeanFactory) otherFactory;
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware...
			setAutowireCandidateResolver(otherListableFactory.getAutowireCandidateResolver().cloneIfNecessary());
			// Make resolvable dependencies (e.g. ResourceLoader) available here as well...
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return new BeanObjectProvider<T>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			public T getObject(Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			@Nullable
			public T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			@Nullable
			public T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			public Stream<T> stream() {
				return Arrays.stream(getBeanNamesForTypedStream(requiredType))
						.map(name -> (T) getBean(name))
						.filter(bean -> !(bean instanceof NullBean));
			}
			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = getBeanNamesForTypedStream(requiredType);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = new LinkedHashMap<>(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = getBean(beanName);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	@Nullable
	private <T> T resolveBean(ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			return ((DefaultListableBeanFactory) parent).resolveBean(requiredType, args, nonUniqueAsNull);
		}
		else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			}
			else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] getBeanNamesForTypedStream(ResolvableType requiredType) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		}
		else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		return getBeanNamesForType(type, true, true);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		Class<?> resolved = type.resolve();
		if (resolved != null && !type.hasGenerics()) {
			return getBeanNamesForType(resolved, includeNonSingletons, allowEagerInit);
		}
		else {
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		//isConfigurationFrozen：如果没有冻结bean定义，在finishBeanFactoryInitialization方法中就被设置为true了
		//如果type类型为null或者不允许急切初始化，直接主动查找
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			//进这里
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		//根据includeNonSingletons尝试从allBeanNamesByType或者singletonBeanNamesByType缓存中获取
		//这两个缓存我们前面就见过，折现我们可以知道他们的作用就是方法精心干类型查找beanName
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		//根据类型返回匹配的resolvedBeanNames数组
		String[] resolvedBeanNames = cache.get(type);
		//不为null就返回找到的数组
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		//缓存没找到就主动查找
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			//将结果放入缓存
			cache.put(type, resolvedBeanNames);
		}
		return resolvedBeanNames;
	}

	// * 将会从所有注册的beanName缓存（包括自动注册的以及好手动注册的）中查找（不包括别名），返回与给定类型匹配(包括子类)的bean的名称
	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		List<String> result = new ArrayList<>();

		// Check all bean definitions.
		/*
		 * 1 检查所有beanDefinitionNames的缓存
		 *
		 */
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name is not defined as alias for some other bean.
			//之地不是别名的beanName进行操作
			if (!isAlias(beanName)) {
				try {
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					//检查符合规则的 bean 定义：
					//不是抽象的，并且（允许急切初始化，或者（指定class或者不是lazy-init或者允许工厂急切地加载 bean 实例），并且不需要快速初始化以确定其类型）
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						//是否是FactoryBean
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						boolean matchFound = false;
						//是否允许FactoryBean初始化
						boolean allowFactoryBeanInit = (allowEagerInit || containsSingleton(beanName));
						//bean定义是否不是lazy-init
						boolean isNonLazyDecorated = (dbd != null && !mbd.isLazyInit());
						/*非FactoryBean*/
						if (!isFactoryBean) {
							//如果包含其它类型的bean，或者是单例的
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								//判断类型是否匹配
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						/*FactoryBean*/
						else  {
							//如果包含其它类型的bean，或者不是lazy-init，或者（允许FactoryBean初始化并且是单例的）
							if (includeNonSingletons || isNonLazyDecorated ||
									(allowFactoryBeanInit && isSingleton(beanName, mbd, dbd))) {
								//判断类型是否匹配
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							//如果没有匹配，检查FactoryBean本身
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								beanName = FACTORY_BEAN_PREFIX + beanName;
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						//如果匹配，那么加入结果集合
						if (matchFound) {
							result.add(beanName);
						}
					}
				}
				catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					LogMessage message = (ex instanceof CannotLoadBeanClassException ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName));
					logger.trace(message, ex);
					// Register exception, in case the bean was accidentally unresolvable.
					onSuppressedException(ex);
				}
			}
		}

		// Check manually registered singletons too.
		/*
		 * 2 检查所有手动注册的单例
		 */
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				// 在属于FactoryBean的情况下，匹配由FactoryBean创建的对象。
				if (isFactoryBean(beanName)) {
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				//匹配原始 bean 实例（可能是原始FactoryBean）
				if (isTypeMatch(beanName, type)) {
					result.add(beanName);
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				logger.trace(LogMessage.format(
						"Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}

		return StringUtils.toStringArray(result);
	}

	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 * defines a factory method for
	 * @return whether eager initialization is necessary
	 */
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(
			@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit) throws BeansException {

		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		Map<String, T> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			try {
				Object beanInstance = getBean(beanName);
				if (!(beanInstance instanceof NullBean)) {
					result.put(beanName, (T) beanInstance);
				}
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException) {
					BeanCreationException bce = (BeanCreationException) rootCause;
					String exBeanName = bce.getBeanName();
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition beanDefinition = getBeanDefinition(beanName);
			if (!beanDefinition.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findMergedAnnotationOnBean(beanName, annotationType)
				.synthesize(MergedAnnotation::isPresent).orElse(null);
	}

	private <A extends Annotation> MergedAnnotation<A> findMergedAnnotationOnBean(
			String beanName, Class<A> annotationType) {

		Class<?> beanType = getType(beanName);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, e.g. in case of a proxy.
			if (bd.hasBeanClass()) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation;
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation;
				}
			}
		}
		return MergedAnnotation.missing();
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	@Override
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(
			String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);
		if (containsBeanDefinition(bdName)) {
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(bdName), descriptor, resolver);
		}
		else if (containsSingleton(beanName)) {
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((DefaultListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor, resolver);
		}
		else if (parent instanceof ConfigurableListableBeanFactory) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			return ((ConfigurableListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor);
		}
		else {
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param mbd the merged bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
			DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);
		resolveBeanClass(mbd, bdName);
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		BeanDefinitionHolder holder = (beanName.equals(bdName) ?
				this.mergedBeanDefinitionHolders.computeIfAbsent(beanName,
						key -> new BeanDefinitionHolder(mbd, beanName, getAliases(bdName))) :
				new BeanDefinitionHolder(mbd, beanName, getAliases(bdName)));
		return resolver.isAutowireCandidate(holder, descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		super.clearMetadataCache();
		this.mergedBeanDefinitionHolders.clear();
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		this.configurationFrozen = true;
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}

	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}

		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		//保存beanDefinitionNames的副本用于后续的遍历，以允许init等方法注册新的bean定义
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		/*
		 * 1 遍历beanDefinitionNames集合，触发所有非延迟加载的的单例bean的实例化和初始化
		 */
		for (String beanName : beanNames) {
			//这里已经在扫描那合并了
			//获取该beanName的已合并的bean定义bd，就是当前bean定义与"parent"属性指定的bean定义合并之后的bean定义
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			//如果bd不是抽象的，即abstract不为true，并且bd是单例的，并且bd是非延迟加载的
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				/*如果当前beanName对应的bean是FactoryBean*/
				if (isFactoryBean(beanName)) {
					//通过getBean(&beanName)获取FactoryBean本身的实例
					Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
					//如果输入FactoryBean
					if (bean instanceof FactoryBean) {
						FactoryBean<?> factory = (FactoryBean<?>) bean;
						//该boolean类型的标志位用于判断这个FactoryBean是否希望立即初始化通过getObject()方法自定义返回的对象
						boolean isEagerInit;
						//系统安全管理器相关
						if (System.getSecurityManager() != null && factory instanceof SmartFactoryBean) {
							isEagerInit = AccessController.doPrivileged(
									(PrivilegedAction<Boolean>) ((SmartFactoryBean<?>) factory)::isEagerInit,
									getAccessControlContext());
						}
						else {
							//是否属于SmartFactoryBean，如果属于，那么获取isEagerInit的返回值
							isEagerInit = (factory instanceof SmartFactoryBean &&
									((SmartFactoryBean<?>) factory).isEagerInit());
						}
						//如果为true，那么初始化通过getObject()方法自定义返回的对象
						//对于普通的FactoryBean一般都是false
						if (isEagerInit) {
							getBean(beanName);
						}
					}
				}
				else {
					//开始实例普通的bean
					//核心方法,调用getBean方法，用于初始化
					getBean(beanName);
				}
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		/*
		 * 2 遍历beanDefinitionNames集合，触发所有SmartInitializingSingleton类型的bean实例的afterSingletonsInstantiated方法回调
		 */
		for (String beanName : beanNames) {
			//获取单例beanName对应的实例singletonInstance
			Object singletonInstance = getSingleton(beanName);
			//如果singletonInstance属于SmartInitializingSingleton类型
			if (singletonInstance instanceof SmartInitializingSingleton) {
				SmartInitializingSingleton smartSingleton = (SmartInitializingSingleton) singletonInstance;
				//回调afterSingletonsInstantiated方法，即在bean实例化之后回调该方法
				//这是一个扩展点，对于所有非延迟初始化的SmartInitializingSingleton类型的单例bean初始化完毕之后会进行回调
				if (System.getSecurityManager() != null) {
					AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
						smartSingleton.afterSingletonsInstantiated();
						return null;
					}, getAccessControlContext());
				}
				else {
					smartSingleton.afterSingletonsInstantiated();
				}
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");
		//bean definition注册前的校验，methodOverrides校验
		if (beanDefinition instanceof AbstractBeanDefinition) {
			try {
				((AbstractBeanDefinition) beanDefinition).validate();
			}
			catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}
		//尝试从注册表缓存中查找当前beanName的BeanDefinition
		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		/*
		 * 1 如果找到了同名的BeanDefinition，进行ban定义覆盖的校验和操作
		 */
		if (existingDefinition != null) {
			/*
			 * 判断是否不允许 bean 的覆盖
			 *
			 * allowBeanDefinitionOverriding属性我们在“customizeBeanFactory配置beanFactory”的部分已经讲过
			 * 普通应用默认为true，boot应用默认false，可以自定义配置
			 */
			if (!isAllowBeanDefinitionOverriding()) {
				//如果不允许，那么就是出现同名bean，那么直接抛出异常
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			/*
			 * 否则，表示允许，继续判断角色相关，不必关心
			 * 打印日志，用框架定义的 Bean 覆盖用户自定义的 Bean
			 */
			else if (existingDefinition.getRole() < beanDefinition.getRole()) {
				// e.g. was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
				if (logger.isInfoEnabled()) {
					logger.info("Overriding user-defined bean definition for bean '" + beanName +
							"' with a framework-generated bean definition: replacing [" +
							existingDefinition + "] with [" + beanDefinition + "]");
				}
			}
			/*
			 * 否则，表示允许，继续如果当前的beanDefinition不等于找到的此前的existingDefinition
			 * 打印日志，将会使用新beanDefinition覆盖旧的beanDefinition
			 */
			else if (!beanDefinition.equals(existingDefinition)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Overriding bean definition for bean '" + beanName +
							"' with a different definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			/*
			 * 否则，表示允许，打印日志，将会使用同样（equals比较返回true）新的beanDefinition覆盖旧的beanDefinition
			 */
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Overriding bean definition for bean '" + beanName +
							"' with an equivalent definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			//使用新的beanDefinition覆盖旧的existingDefinition
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}
		/*2 如果没找到同名的BeanDefinition，这是正常情况*/
		else {
			/*如果已经有其他任何bean实例开始初始化了*/
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				synchronized (this.beanDefinitionMap) {
					//当前的beanName和beanDefinition存入beanDefinitionMap缓存
					this.beanDefinitionMap.put(beanName, beanDefinition);
					//当前的beanName存入beanDefinitionNames缓存
					//重新生成一个list集合替换
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					this.beanDefinitionNames = updatedDefinitions;
					//当前的beanName从手动注册bean名称集合manualSingletonNames缓存中移除
					//因为如果这里自动注册了beanName，那么需要从manualSingletonNames缓存中移除代表手动注册的单例beanName。
					removeManualSingletonName(beanName);
				}
			}
			/*否则，其他任何bean实例没有开始初始化*/
			else {
				// Still in startup registration phase
				//仍处于启动注册阶段，不加锁
				//当前的beanName和beanDefinition存入beanDefinitionMap缓存
				this.beanDefinitionMap.put(beanName, beanDefinition);
				//当前的beanName存入beanDefinitionNames缓存
				this.beanDefinitionNames.add(beanName);
				//当前的beanName从手动注册bean名称集合manualSingletonNames缓存中移除
				//因为如果这里自动注册了beanName，那么需要从manualSingletonNames缓存中移除代表手动注册的单例beanName。
				removeManualSingletonName(beanName);
			}
			//仅仅在与初始化时才会使用到，很少使用
			this.frozenBeanDefinitionNames = null;
		}
		/*
		 * 3 如果找到的旧的BeanDefinition不为null，或者单例bean实例的缓存singletonObjects已中包含给定beanName的实例
		 * 那么将当前beanName对应的在DefaultSingletonBeanRegistry中的实例缓存清除，需要重新生成实例
		 */
		if (existingDefinition != null || containsSingleton(beanName)) {
			/*
			 * 将当前beanName对应的在DefaultSingletonBeanRegistry中的实例缓存清除
			 * 重置给定 beanName 的所有 bean 定义缓存，包括从它派生的 bean 的缓存(merge)。
			 * 以及重置以给定beanName为父类bean的子类Bean缓存。
			 */
			resetBeanDefinition(beanName);
		}
		/*
		 * 4 否则，如果此工厂的 bean 定义是否冻结，即不应进一步修改或后处理。
		 * 那么删除所有的按类型映射的任何缓存：allBeanNamesByType和singletonBeanNamesByType
		 * 在finishBeanFactoryInitialization方法中就会冻结 bean 定义，并且进行bean初始化操作
		 * 一般不会出现
		 */
		else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		}
		else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	/**
	 * DefaultListableBeanFactory的方法
	 * <p>
	 * 重置给定 bean 的所有 bean 定义缓存，包括从它派生的 bean 的缓存。
	 * 通知所有后处理器已重置指定的 bean 定义。
	 *
	 * @param beanName 要重置的 bean 的名称
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		//删除给定beanName的mergedBeanDefinitions的缓存，这是已合并的bean定义缓存
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		//从单例缓存中删除相应的单例（如果有），这个方法之前讲过了
		//实际上就是删除DefaultSingletonBeanRegistry中的关于单例bean实现的缓存
		destroySingleton(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		//通知所有后处理器已重置指定的 bean 定义。
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		// 重置所有以当前beanName为父类bean的子类Bean
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification
				// of the beanDefinitionMap.
				//如果 bd 不为null，并且给定beanName等于bd的parentName属性
				if (bd != null && beanName.equals(bd.getParentName())) {
					//递归调用resetBeanDefinition重置BeanDefinition
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		//调用父类的方法DefaultSingletonBeanRegistry注册到父类的相应的bean实例缓存中
		super.registerSingleton(beanName, singletonObject);
		//更新DefaultListableBeanFactory工厂内部的手动注册的单例bean的名字缓存manualSingletonNames
		//如果不包含给定beanName，那么添加beanName
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		//删除有关按类型映射的任何缓存，即清空allBeanNamesByType和singletonBeanNamesByType属性集合
		clearByTypeCache();
	}

	@Override
	public void destroySingletons() {
		super.destroySingletons();
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		removeManualSingletonName(beanName);
		clearByTypeCache();
	}

	private void removeManualSingletonName(String beanName) {
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}

	/**
	 * Update the factory's internal set of manual singleton names.
	 * @param action the modification action
	 * @param condition a precondition for the modification action
	 * (if this condition does not apply, the action can be skipped)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				if (condition.test(this.manualSingletonNames)) {
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					action.accept(updatedSingletons);
					this.manualSingletonNames = updatedSingletons;
				}
			}
		}
		else {
			// Still in startup registration phase
			if (condition.test(this.manualSingletonNames)) {
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory) {
			return ((AutowireCapableBeanFactory) parent).resolveNamedBean(requiredType);
		}
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) throws BeansException {

		Assert.notNull(requiredType, "Required type must not be null");
		String[] candidateNames = getBeanNamesForType(requiredType);

		if (candidateNames.length > 1) {
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					autowireCandidates.add(beanName);
				}
			}
			if (!autowireCandidates.isEmpty()) {
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		if (candidateNames.length == 1) {
			String beanName = candidateNames[0];
			return new NamedBeanHolder<>(beanName, (T) getBean(beanName, requiredType.toClass(), args));
		}
		else if (candidateNames.length > 1) {
			Map<String, Object> candidates = new LinkedHashMap<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (containsSingleton(beanName) && args == null) {
					Object beanInstance = getBean(beanName);
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				}
				else {
					candidates.put(beanName, getType(beanName));
				}
			}
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			if (candidateName == null) {
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			if (candidateName != null) {
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null || beanInstance instanceof Class) {
					beanInstance = getBean(candidateName, requiredType.toClass(), args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			if (!nonUniqueAsNull) {
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}

		return null;
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		//初始化descriptor
		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		/*1 如果依赖的类型为Optional类型，Java8的新特性，可以避免由于没有找到依赖而抛出异常*/
		if (Optional.class == descriptor.getDependencyType()) {
			//那么返回一个被Optional包装的依赖项，因此允许找不到依赖项，也就不会抛出异常了，这个我们在IoC学习的时候已经讲过了
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		/*2 如果依赖的类型为ObjectFactory类型或者ObjectProvider类型*/
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			//返回一个DependencyObjectProvider对象，这是一个可序列化ObjectFactory/ObjectProvider提供器，用于延迟解决依赖项。
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		/*3 如果依赖的类型为javax.inject.Provider类型*/
		else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		}
		/*4 如果依赖的类型为其它类型，那么走下面的逻辑，这也是大部分的逻辑*/
		else {
			//当属性加了@Lazy时，这里不为null，是一个cglib代理对象。
			//获取autowireCandidateResolver，调用getLazyResolutionProxyIfNecessary方法，如果有必要（存在@Lazy注解），则获取延迟加载的代理对象
			//Spring不能自己解决构造器循环依赖，但是，我们添加@Lazy注解即可解决构造器循环依赖，其实现就是返回代理对象，走的就是这个逻辑
			//autowireCandidateResolver默认是SimpleAutowireCandidateResolver类型的实例，那么该方法固定返回null，如果开启了注解支持
			//那么在前面讲的registerAnnotationConfigProcessors方法中会默认注入的是一个ContextAnnotationAutowireCandidateResolver实例
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			//如果结果为null，表示没有延迟加载
			if (result == null) {
				//进这
				//调用doResolveDependency解析依赖，获取依赖的对象，这一步可能会初始化所依赖的bean
				result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
			}
			return result;
		}
	}

	/**
	 * 可以看到无论是代理对象还是非代理对象，内部都涉及到doResolveDependency方法，在解析注解注入的后处理器比如CommonAnnotationBeanPostProcessor、AutowiredAnnotationBeanPostProcessor中都也会调用该方法，足以说明该方法的重要性。
	 *   以do开头的方法，是真正的根据此工厂中的 bean定义解析指定的依赖项的方法的实现。当然，它的实现更加复杂一点，它会从各种情况中获取依赖，除了从IoC容器查找之外还有从@Value注解中查找，并且它的优先级更高。
	 *   实际上，很多的自动注入模式的依赖查找都调用了该方法，除了目前正在讲的基于XML和注解配置的构造器自动注入之外，后面我们会讲到的@Autowired注解的setter方法注入、属性反射注入，XML配置的基于byType的setter方法自动注入（直接调用外层resolveDependency方法，并且不会走最后的banName查找），@Resource注解的byType注入（直接调用外层resolveDependency方法）等等注入逻辑内部也都会调用该方法。其他自动注入模式，比如XML配置的基于byName的setter方法自动注入则有自己的逻辑。因此，该方法尤为重要！
	 *
	 *   该方法的大概查找过程如下：
	 *
	 * 首先是descriptor调用resolveShortcut方法，直接尝试快速查找。一般的descriptor都是DependencyDescriptor类型，该方法会直接返回null，只有AutowiredAnnotationBeanPostProcessor中会用到ShortcutDependencyDescriptor，内部会调用beanFactory.getBean(String name, Class< T > requiredType)方法。
	 * AutowiredAnnotationBeanPostProcessor后处理器是开启注解支持后通过registerAnnotationConfigProcessors方法添加的，用于解析字段或者方法上的@Autowired、@Value、@Inject等注解，通过注解进行依赖注入。第一次解析依赖之后会将找到的依赖封装为封装成 ShortcutDependencyDescriptor类型，存入cachedFieldValue缓存，下次注入时直接从缓存中查找，找到了就直接返回。构造器自动注入不会出现这种情况，即使存在@Autowired、@Value、@Inject等注解。这些东西在后面的populateBean部分会讲到！
	 * 调用getSuggestedValue尝试获取@Value注解的值进行注入。默认是SimpleAutowireCandidateResolver类型的实例，那么该方法固定返回null，如果开启了注解支持，那么会替换为ContextAnnotationAutowireCandidateResolver，它的父类QualifierAnnotationAutowireCandidateResolver的getSuggestedValue方法中才会具有查找@Value注解的功能。找到了就直接返回。
	 * 3 调用resolveMultipleBeans，尝试解析Stream、Array、Collection（必须是接口）、Map类型的集合依赖，找到了就直接返回对应的集合，其中返回的全部是对象实例。
	 * 尝试解析普通依赖，以及处理没找到依赖的情况，首先调用findAutowireCandidates方法根据类型匹配查找与所需类型匹配的 bean 实例或者 bean的Class：
	 * 如果没找到任何依赖，且是非必需依赖，那么返回null，如果是必须依赖则抛出异常。
	 * 如果找到了一个依赖项，那么该依赖作为最合适的依赖项进行实例化并返回。
	 * 如果找到了多个依赖项，那么继续调用determineAutowireCandidate方法根据@Primary、@Priority、resolvableDependencies缓存、banName顺序依次解析找到最合适的一个依赖：
	 * 找不到的话该方法就返回null。后续判断如果该依赖是必须依赖，或者不是集合依赖(如果是Collection还必须是接口)，那将在resolveNotUnique方法中抛出NoUniqueBeanDefinitionException异常
	 * 找到之后就尝试实例化该依赖项并返回。
	 * 注意，对于XML配置的byType的setter方法自动注入，最后一步banName查找不会生效，其他的模式：XML的构造器注入、注解的构造器、setter方法、属性反射注入，都会走最后一步banName查找的逻辑。
	 *
	 */
	@Nullable
	public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		//设置当前的descriptor作为InjectionPoint，返回前一个InjectionPoint
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			/*
			 * 1 根据名称快速查找依赖项
			 * 如果当前descriptor为ShortcutDependencyDescriptor类型，那么相当于直接调用beanFactory.getBean(String name, Class<T> requiredType)方法
			 * 一般都是DependencyDescriptor类型，因此会返回null，只有AutowiredAnnotationBeanPostProcessor中会用到ShortcutDependencyDescriptor
			 * AutowiredAnnotationBeanPostProcessor用于解析字段或者方法上的@Autowired、@Value、@Inject等注解，也是进行依赖注入的
			 */
			Object shortcut = descriptor.resolveShortcut(this);
			//如果找到了依赖，直接返回，一般都是null
			if (shortcut != null) {
				return shortcut;
			}
			/*
			 * 获取descriptor包装的依赖的类型
			 */
			Class<?> type = descriptor.getDependencyType();
			/*
			 * 2 尝试获取@Value注解的值进行注入，QualifierAnnotationAutowireCandidateResolver的方法
			 *
			 * 获取autowireCandidateResolver，默认是SimpleAutowireCandidateResolver类型的实例，那么该方法固定返回null，如果开启了注解支持
			 * 那么在前面讲的registerAnnotationConfigProcessors方法中会默认注入的是一个ContextAnnotationAutowireCandidateResolver实例
			 * 最后是调用父类QualifierAnnotationAutowireCandidateResolver的getSuggestedValue方法实现
			 * QualifierAnnotationAutowireCandidateResolver主要处理@Qualifier、@Value以及JSR-330的javax.inject.Qualifier注解
			 *
			 * 这里的getSuggestedValue方法就是处理@Value注解，读取 @Value 对应的值（如果存在），用于后续进行依赖注入，没有就返回null
			 */
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
			//如果value不为null，说明存在@Value注解，并且获取到了值
			if (value != null) {
				if (value instanceof String) {
					/*
					 * 这里就是解析value值中的占位符的逻辑，将占位符替换为属性值，关键方法就是resolveStringValue
					 * 因此@Value支持占位符，即${.. : ..}，占位符的语法和解析之前就学过了，这里的占位符支持普通方式从外部配置文件中加载进来的属性以及environment的属性。
					 */
					String strVal = resolveEmbeddedValue((String) value);
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					/*
					 * 这里就是解析value值中的SPEL表达式的逻辑，将SPEL表达式解析为指定值
					 *
					 * 使用默认StandardBeanExpressionResolver(在prepareBeanFactory方法中注册的)解析value值中SPEL表达式
					 * 因此@Value支持SPEL表达式，即#{}，SPEL表达式的语法之前就学过了
					 *
					 */
					value = evaluateBeanDefinitionString(strVal, bd);
				}
				//获取类型装换器，做类型转换处理
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				try {
					//尝试将解析后的value值转换为依赖项的类型，并返回
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
				}
				catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					//如果转换失败，使用不支持类型描述器分辨率的自定义类型转换器转换并返回
					return (descriptor.getField() != null ?
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
				}
			}
			//注入多个相同类型的bean List<I> list 比如I有多个实现类 就会把这些实现类注入进来
			//注入多个bean  比如list set
			/*
			 * 3 尝试解析集合依赖：Stream、Array、Collection、Map
			 * Spring会将符合条件的bean都注入到集合中
			 */
			//解析集合依赖，返回的就是所需的依赖参数
			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			//如果找到了依赖，那么直接返回，没找到，那么继续下一步
			if (multipleBeans != null) {
				return multipleBeans;
			}

			//这里已经找到UserService
			//如果注入接口I 名字叫y 而I有两个实现类x和y  这里就会找出两个class
			/*
			 * 4 尝试解析普通单个依赖
			 */

			//容器中根据type（类型）查找满足条件的候选beanName及其对应的的实例或者Class对象映射map，返回一个Map<BeanName, BeanInstance>
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			/*如果是空集合*/
			if (matchingBeans.isEmpty()) {
				//通过isRequired判断该依赖项是否是必须的，可通过@Autowired(required = false)设置为非必须依赖
				if (isRequired(descriptor)) {
					//对于无法解析（没找到）的依赖，引发NoSuchBeanDefinitionException 或者 BeanNotOfRequiredTypeException异常
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				return null;
			}
			/*
			 * 到这里，表示找到了依赖，如果有多个依赖那么需要选择某个
			 */
			//最终依赖beanName
			String autowiredBeanName;
			//最终依赖的实例或者Class
			Object instanceCandidate;
			/*如果候选依赖大于一个*/
			if (matchingBeans.size() > 1) {
				//找出注入的属性名字y
				//@Primary、@Priority、resolvableDependencies缓存、banName顺序依次解析
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				//如果autowiredBeanName为null，表示没找到
				if (autowiredBeanName == null) {
					//如果该依赖是必须的，或者不是集合依赖(如果是Collection还必须是接口)
					//将在resolveNotUnique方法中抛出NoUniqueBeanDefinitionException异常
					if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
					}
					//如果不是必须的，或者是符合条件的集合依赖，将返回null，即最终注入空集合，不会抛出异常
					else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						return null;
					}
				}
				//根据名字y从根据类型找出来的map<beanName,class>当中获取class
				//获取候选依赖value，可能是实例或者class
				instanceCandidate = matchingBeans.get(autowiredBeanName);
			}
			/*如果候选依赖只有一个，那么该候选依赖为最终依赖*/
			else {
				// We have exactly one match.
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				autowiredBeanName = entry.getKey();
				instanceCandidate = entry.getValue();
			}
			//如果autowiredBeanNames不为null，那么将最后候选依赖名字加入自动注入的beanName的集合
			if (autowiredBeanNames != null) {
				autowiredBeanNames.add(autowiredBeanName);
			}
			//如果instanceCandidate属于Class，那么反射实例化
			if (instanceCandidate instanceof Class) {
				//进这 getBean 从spring容器拿
				//根据class从spring容器中找到bean getBean
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
			}
			//最终结果result，该结果就是要注入的依赖项的实例
			Object result = instanceCandidate;
			//如果是NullBean，表示没有从容器中获取到的实例为null
			if (result instanceof NullBean) {
				//如果该依赖项是必须的,那么抛出NoSuchBeanDefinitionException或者BeanNotOfRequiredTypeException异常
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				//如果不是必须的，那么返回null
				result = null;
			}
			//如果result不属于依赖的类型，抛出BeanNotOfRequiredTypeException异常
			if (!ClassUtils.isAssignableValue(type, result)) {
				throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
			}
			//返回result，依赖项查找完毕
			return result;
		}
		finally {
			//无论是正常返回还是抛出异常都会走该逻辑，将注入点修改为原来的注入点
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Nullable
	private Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {
		//获取依赖类型
		Class<?> type = descriptor.getDependencyType();

		/*
		 * 1 Stream处理
		 * 如果descriptor属于StreamDependencyDescriptor，这是用于Stream流访问多个元素的依赖项描述符标记
		 * 一般都是DependencyDescriptor类型，在resolveStream方法中会使用StreamDependencyDescriptor类型
		 *
		 * 通过findAutowireCandidates查找符合条件的bean集合，最终将会返回一个Stream，包含了全部找到的bean实例
		 */
		if (descriptor instanceof StreamDependencyDescriptor) {
			//容器中根据type查找满足条件的候选bean名称和候选bean实例的映射map，返回一个Map<BeanName, BeanInstance>
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			//加入到autowiredBeanNames集合中
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name -> descriptor.resolveCandidate(name, type, this))
					.filter(bean -> !(bean instanceof NullBean));
			//通过dependencyComparator比较器排序，也就是按照Order排序，之前讲过了
			//dependencyComparator默认是null，也就是不排序，除非手动设置或者开启注解支持
			//开启注解支持时将会在registerAnnotationConfigProcessors方法中注入AnnotationAwareOrderComparator比较器，支持注解排序
			if (((StreamDependencyDescriptor) descriptor).isOrdered()) {
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
			}
			return stream;
		}
		/*
		 * 2 Array处理
		 *
		 * 通过findAutowireCandidates查找符合条件的bean集合，最终将会返回一个Array，包含了全部找到的bean实例
		 */
		else if (type.isArray()) {
			Class<?> componentType = type.getComponentType();
			ResolvableType resolvableType = descriptor.getResolvableType();
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			if (resolvedArrayType != type) {
				componentType = resolvableType.getComponentType().resolve();
			}
			if (componentType == null) {
				return null;
			}
			//容器中根据type查找满足条件的候选bean名称和候选bean实例的映射map，返回一个Map<BeanName, BeanInstance>
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			//加入到autowiredBeanNames集合中
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//map的value转换类型
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			//通过dependencyComparator比较器排序，也就是按照Order排序，之前讲过了
			//dependencyComparator默认是null，也就是不排序，除非手动设置或者开启注解支持
			//开启注解支持时将会在registerAnnotationConfigProcessors方法中注入AnnotationAwareOrderComparator比较器，支持注解排序
			if (result instanceof Object[]) {
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				if (comparator != null) {
					Arrays.sort((Object[]) result, comparator);
				}
			}
			return result;
		}
		/*
		 * 3 Collection处理
		 *
		 * 通过findAutowireCandidates查找符合条件的bean集合，最终将会返回一个对应类型的集合，包含了全部找到的bean实例
		 * 注意Collection类型还必须是接口
		 */
		else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			//Collection元素泛型类型
			Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
			if (elementType == null) {
				return null;
			}
			//容器中根据type查找满足条件的候选bean名称和候选bean实例的映射map，返回一个Map<BeanName, BeanInstance>
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			//加入到autowiredBeanNames集合中
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//map的value转换类型
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), type);
			//通过dependencyComparator比较器排序，也就是按照Order排序，之前讲过了
			//dependencyComparator默认是null，也就是不排序，除非手动设置或者开启注解支持
			//开启注解支持时将会在registerAnnotationConfigProcessors方法中注入AnnotationAwareOrderComparator比较器，支持注解排序
			if (result instanceof List) {
				if (((List<?>) result).size() > 1) {
					Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
					if (comparator != null) {
						((List<?>) result).sort(comparator);
					}
				}
			}
			return result;
		}
		/*
		 * 4 Map处理
		 *
		 * 通过findAutowireCandidates查找符合条件的bean集合，最终将会返回一个Map，包含了全部找到的bean实例
		 */
		else if (Map.class == type) {
			//Map元素泛型类型
			ResolvableType mapType = descriptor.getResolvableType().asMap();
			//key类型
			Class<?> keyType = mapType.resolveGeneric(0);
			if (String.class != keyType) {
				return null;
			}
			//value类型
			Class<?> valueType = mapType.resolveGeneric(1);
			if (valueType == null) {
				return null;
			}
			//容器中根据type查找满足条件的候选bean名称和候选bean实例的映射map，返回一个Map<BeanName, BeanInstance>
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			//加入到autowiredBeanNames集合中
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			return matchingBeans;
		}
		else {
			return null;
		}
	}

	private boolean isRequired(DependencyDescriptor descriptor) {
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	private boolean indicatesMultipleBeans(Class<?> type) {
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}

	@Nullable
	private Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		}
		else {
			return comparator;
		}
	}

	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> dependencyComparator = getDependencyComparator();
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator ?
				(OrderComparator) dependencyComparator : OrderComparator.INSTANCE);
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * @param beanName the name of the bean that is about to be wired
	 * @param requiredType the actual type of bean to look for
	 * (may be an array component type or collection element type)
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 *  * 查找与所需类型匹配的 bean 实例。 在指定 bean 的自动装配期间调用。
	 */
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {

		//找出I的两个实现类x和y 通过属性的类型从bd map中找出实现类的名字
		//获取从所有缓存中给定类型的所有 bean 名称，排除别名，包括在祖先工厂中定义的 bean 名称，
		//包括其他类型的bean（比如原型bean），包括FactoryBean返回的实例类型以及FactoryBean本省，包括工厂方法返回的类型
		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		//新建一个LinkedHashMap用于存放要返回的结果
		Map<String, Object> result = new LinkedHashMap<>(candidateNames.length);
		//遍历resolvableDependencies缓存映射，前面学习的prepareBeanFactory方法中，我们就知道Spring会预先存放四个预定义类型以及实现到该缓存中
		//这个缓存用于存放已经解析的依赖项到相应的自动注入的实例的map，这里就使用到了
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			//获取注入类型
			Class<?> autowiringType = classObjectEntry.getKey();
			//如果该类型匹配或兼容当前所需依赖类型
			if (autowiringType.isAssignableFrom(requiredType)) {
				//获取注入的实例
				Object autowiringValue = classObjectEntry.getValue();
				//根据给定的所需类型解析给定的自动装配值
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				//如果当前实例属于requiredType类型
				if (requiredType.isInstance(autowiringValue)) {
					//那么加入结果集，跳出循环
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}
		/*
		 * 遍历找到的beanName集合，进一步筛选有资格的bean
		 */
		for (String candidate : candidateNames) {
			//判断是否不是自引用，以及给定的beanName对应的bean定义是否有资格作为候选bean被自动注入到其他bean中，即XML的autowire-candidate属性
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				//通过属性的名字找出对应的类型 多个名字就多个类型
				//如果有资格，那么将候选beanName及其对应的的实例或者Class对象添加到result中
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}
		/*
		 * 如果结果集为空集，那么可能会放宽条件尽量匹配
		 */
		if (result.isEmpty()) {
			//是否是所需类型是否是集合类型(如果是Collection还必须是接口)
			boolean multiple = indicatesMultipleBeans(requiredType);
			// Consider fallback matches if the first pass failed to find anything...
			//降级匹配，比如强制转型
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();
			for (String candidate : candidateNames) {
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}
			//如果是空集并且不是集合类型，那么尝试自引用匹配
			if (result.isEmpty() && !multiple) {
				// Consider self references as a final pass...
				// but in the case of a dependency collection, not the very same bean itself.
				for (String candidate : candidateNames) {
					if (isSelfReference(beanName, candidate) &&
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 */
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
			DependencyDescriptor descriptor, Class<?> requiredType) {
		/*如果是集合类型描述符，在resolveMultipleBeans方法中会使用集合描述符*/
		if (descriptor instanceof MultiElementDescriptor) {
			//获取bean实例，实际上就是调用beanFactory.getBean(beanName)方法，因此可能会触发bean实例化
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			//如果不是NullBean，即返回的不是null
			if (!(beanInstance instanceof NullBean)) {
				//将实例加入映射中
				candidates.put(candidateName, beanInstance);
			}
		}
		/*否则，如果已初始化该beanName的单例实例，或者是Stream类型描述符*/
		else if (containsSingleton(candidateName) || (descriptor instanceof StreamDependencyDescriptor &&
				((StreamDependencyDescriptor) descriptor).isOrdered())) {
			//获取bean实例，实际上就是调用beanFactory.getBean(beanName)方法，因此可能会触发bean实例化
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			//将实例加入映射中，如果是NullBean则加入null
			candidates.put(candidateName, (beanInstance instanceof NullBean ? null : beanInstance));
		}
		/*否则，直接将找到的beanName所属的Class加入map映射*/
		else {
			candidates.put(candidateName, getType(candidateName));
		}
	}

	/**
	 * Determine the autowire candidate in the given set of beans.
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 * @param candidates a Map of candidate names and candidate instances
	 * that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the autowire candidate, or {@code null} if none found
	 *  * 确定给定 bean 集合中的候选依赖名称。按照@Primary、@Priority、resolvableDependencies缓存、banName顺序依次解析
	 */
	@Nullable
	protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		//获取依赖类型
		Class<?> requiredType = descriptor.getDependencyType();
		/*
		 * 1 根据@Primary注解标注的类或者primary=true属性标注的bean定义，获取主侯选依赖的beanName
		 * @Primary注解的解析需要开启注解解析支持
		 * 如果候选依赖中存在多个主候选依赖则抛出异常：more than one 'primary' bean found among candidates……
		 *
		 */
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		//如果不为null，那么直接返回主候选依赖beanName
		if (primaryCandidate != null) {
			return primaryCandidate;
		}
		/*
		 * 2 根据@Priority注解标注的类比较优先级，获取侯选依赖的beanName，优先级值越小那么优先级越高。注意@Order注解不起作用
		 * @Priority注解的解析需要AnnotationAwareOrderComparator（开启注解解析支持），默认OrderComparator只会返回null
		 * 如果候选依赖中存在多个候选依赖存在相同的优先级则抛出异常：Multiple beans found with the same priority……
		 */
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		//如果不为null，那么直接返回优先级最高的候选依赖beanName
		if (priorityCandidate != null) {
			return priorityCandidate;
		}
		/*
		 * 3 上面都不找不到合适的候选依赖。最后，使用默认策略
		 *
		 * 3.1 如果resolvableDependencies中已经注册了该候选依赖的依赖关系，则返回该候选依赖的beanName
		 * 3.2 如果需要的注入的属性的名称与某个候选依赖的beanName或者某个别名相同，则返回该候选依赖的beanName
		 */
		// Fallback
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateName = entry.getKey();
			Object beanInstance = entry.getValue();
			//1 首先，如果resolvableDependencies中已经注册了该候选依赖的依赖关系，那么使用直接使用该候选依赖的beanName
			//2 其次，如果需要的注入的属性的名称与某个候选依赖的beanName或者别名相同，则返回该候选依赖的beanName
			if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
					matchesBeanName(candidateName, descriptor.getDependencyName())) {
				//返回该beanName
				return candidateName;
			}
		}
		return null;
	}

	/**
	 * Determine the primary candidate in the given set of beans.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the primary candidate, or {@code null} if none found
	 * @see #isPrimary(String, Object)
	 * 确定给定 bean 集合中的主要候选依赖，即@Primary注解标注的类或者primary=true属性标注的bean定义
	 *  * 如果候选依赖中存在多个主候选依赖则抛出异常：more than one 'primary' bean found among candidates
	 */
	@Nullable
	protected String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		//保存主候选项的名称，同时用于校验唯一性
		String primaryBeanName = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			//当前依赖是否被设置为主要候选依赖，即@Primary注解标注的类或者primary=true属性标注的bean定义
			if (isPrimary(candidateBeanName, beanInstance)) {
				//如果主候选依赖不为null，这说明存在多个主侯选依赖
				if (primaryBeanName != null) {
					//当前beanFactory是否包含当前candidateBeanName的bean定义
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					//当前beanFactory是否包含当前primaryBeanName的bean定义
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					//如果都满足，那么抛出异常
					//即如果候选依赖中存在多个主候选依赖则抛出异常：more than one 'primary' bean found among candidates
					if (candidateLocal && primaryLocal) {
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					}
					/*
					 * 否则，如果candidateLocal为true，primaryLocal为false，这说明当前candidateBeanName的bean定义在当前beanFactory中
					 * 而原来的primaryBeanName的bean定义在父beanFactory中，则将原来的primaryBeanName替换为当前candidateBeanName
					 * 这说明子beanFactory的主候选依赖优先级高于父beanFactory的主候选依赖
					 */
					else if (candidateLocal) {
						primaryBeanName = candidateBeanName;
					}
				}
				//如果主候选依赖为null，那么primaryBeanName设置为当前candidateBeanName
				else {
					primaryBeanName = candidateBeanName;
				}
			}
		}
		//返回唯一的主候选依赖的beanName
		return primaryBeanName;
	}

	/**
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>Based on {@code @javax.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * @see #getPriority(Object)
	 * 根据@javax.annotation.Priority注解确定给定 bean 集合中的优先级最高的候选依赖。根据Ordered接口的定义，值越小，优先级越高
	 * 注意@Order注解不起作用，@Priority注解的解析需要AnnotationAwareOrderComparator（同样是开启注解解析支持），默认OrderComparator只会返回null。
	 * 如果候选依赖中存在多个候选依赖具有相同的优先级则抛出异常：Multiple beans found with the same priority……。找到就返回，找不到则继续向下查找。
	 */
	@Nullable
	protected String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		//最高优先级的beanName
		String highestPriorityBeanName = null;
		//最高优先级值
		Integer highestPriority = null;
		/*遍历candidates映射，查找最高优先级的beanName*/
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null) {
				//通过dependencyComparator获取@javax.annotation.Priority注解的值，dependencyComparator默认为null因此返回null
				//开启注解支持之后会使用设置为AnnotationAwareOrderComparator，那里面能够解析@javax.annotation.Priority注解
				Integer candidatePriority = getPriority(beanInstance);
				//如果candidatePriority不为null，即设置了@javax.annotation.Priority注解
				if (candidatePriority != null) {
					//如果highestPriorityBeanName不为null
					if (highestPriorityBeanName != null) {
						//如果两个优先级相等，那么抛出异常：Multiple beans found with the same priority
						if (candidatePriority.equals(highestPriority)) {
							throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
									"Multiple beans found with the same priority ('" + highestPriority +
									"') among candidates: " + candidates.keySet());
						}
						//如果当前优先级数值更小，那么级别更高，替换为当前的依赖项的beanName以及优先级
						else if (candidatePriority < highestPriority) {
							highestPriorityBeanName = candidateBeanName;
							highestPriority = candidatePriority;
						}
					}
					//如果highestPriorityBeanName为null，说明是第一次设置，那么直接设置为当前的依赖项的beanName以及优先级
					else {
						highestPriorityBeanName = candidateBeanName;
						highestPriority = candidatePriority;
					}
				}
			}
		}
		//返回最高优先级的beanName
		return highestPriorityBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance (can be null)
	 * @return whether the given bean qualifies as primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		BeanFactory parent = getParentBeanFactory();
		return (parent instanceof DefaultListableBeanFactory &&
				((DefaultListableBeanFactory) parent).isPrimary(transformedBeanName, beanInstance));
	}

	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code javax.annotation.Priority} annotation.
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 * @param beanInstance the bean instance to check (can be {@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 */
	@Nullable
	protected Integer getPriority(Object beanInstance) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).getPriority(beanInstance);
		}
		return null;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 */
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		checkBeanNotOfRequiredType(type, descriptor);

		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
				"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		for (String beanName : this.beanDefinitionNames) {
			RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
			Class<?> targetType = mbd.getTargetType();
			if (targetType != null && type.isAssignableFrom(targetType) &&
					isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
				// Probably a proxy interfering with target type match -> throw meaningful exception.
				Object beanInstance = getSingleton(beanName, false);
				Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
						beanInstance.getClass() : predictBeanType(beanName, mbd));
				if (beanType != null && !type.isAssignableFrom(beanType)) {
					throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
				}
			}
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			((DefaultListableBeanFactory) parent).checkBeanNotOfRequiredType(type, descriptor);
		}
	}

	/**
	 * Create an {@link Optional} wrapper for the specified dependency.
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final Object... args) {

		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			@Override
			public boolean isRequired() {
				return false;
			}
			@Override
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
		};
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		return (result instanceof Optional ? (Optional<?>) result : Optional.ofNullable(result));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		}
		else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		}
		else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}


	/**
	 * A dependency descriptor marker for nested elements.
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		public NestedDependencyDescriptor(DependencyDescriptor original) {
			super(original);
			increaseNestingLevel();
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		public MultiElementDescriptor(DependencyDescriptor original) {
			super(original);
		}
	}


	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		private final boolean ordered;

		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			super(original);
			this.ordered = ordered;
		}

		public boolean isOrdered() {
			return this.ordered;
		}
	}


	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}


	/**
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		private final DependencyDescriptor descriptor;

		private final boolean optional;

		@Nullable
		private final String beanName;

		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		@Override
		public Object getObject() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public Object getObject(final Object... args) throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName, args);
			}
			else {
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		@Nullable
		public Object getIfAvailable() throws BeansException {
			try {
				if (this.optional) {
					return createOptionalDependency(this.descriptor, this.beanName);
				}
				else {
					DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
						@Override
						public boolean isRequired() {
							return false;
						}
					};
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifAvailable(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfAvailable();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Override
		@Nullable
		public Object getIfUnique() throws BeansException {
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				@Override
				public boolean isRequired() {
					return false;
				}
				@Override
				@Nullable
				public Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			try {
				if (this.optional) {
					return createOptionalDependency(descriptorToUse, this.beanName);
				}
				else {
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifUnique(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfUnique();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Nullable
		protected Object getValue() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		@Override
		public Stream<Object> stream() {
			return resolveStream(false);
		}

		@Override
		public Stream<Object> orderedStream() {
			return resolveStream(true);
		}

		@SuppressWarnings("unchecked")
		private Stream<Object> resolveStream(boolean ordered) {
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			return (result instanceof Stream ? (Stream<Object>) result : Stream.of(result));
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code javax.inject} API.
	 * Actual {@code javax.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 */
	private class Jsr330Factory implements Serializable {

		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			return new Jsr330Provider(descriptor, beanName);
		}

		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			@Override
			@Nullable
			public Object get() throws BeansException {
				return getValue();
			}
		}
	}


	/**
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it. This essentially allows for the following construct:
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		private final Map<Object, String> instancesToBeanNames;

		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		@Nullable
		public Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			if (beanName == null || !containsBeanDefinition(beanName)) {
				return null;
			}
			RootBeanDefinition beanDefinition = getMergedLocalBeanDefinition(beanName);
			List<Object> sources = new ArrayList<>(2);
			Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				sources.add(factoryMethod);
			}
			Class<?> targetType = beanDefinition.getTargetType();
			if (targetType != null && targetType != obj.getClass()) {
				sources.add(targetType);
			}
			return sources.toArray();
		}
	}

}
