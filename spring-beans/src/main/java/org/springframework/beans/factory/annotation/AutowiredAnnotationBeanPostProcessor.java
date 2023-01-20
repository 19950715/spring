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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = new LinkedHashSet<>(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	private final Set<String> lookupMethodsChecked = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
	 * if available.
	 */
	/**
	 * AutowiredAnnotationBeanPostProcessor的构造器
	 * <p>
	 * 创建一个新的AutowiredAnnotationBeanPostProcessor
	 * 尝试将@Autowired、@Value、@Inject的Class加入autowiredAnnotationTypes
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		//首先加入Autowired.class
		this.autowiredAnnotationTypes.add(Autowired.class);
		//然后加入Value.class
		this.autowiredAnnotationTypes.add(Value.class);
		try {
			//最后加入javax.inject.Inject.class，如果存在（必须引入支持JSR-330 API的依赖）
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("JSR-330 'javax.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as JSR-330's {@link javax.inject.Inject @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		//找出所有需要完成注入的属性和方法（@Autowiring修饰的），为了将来可以填充
		//处理自动注入注解@Autowired、@Value、@Inject，返回自动注入点的InjectionMetadata元数据对象
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		//检查配置，和CommonAnnotationBeanPostProcessor的同名方法具有一样的逻辑
		metadata.checkConfigMembers(beanDefinition);
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	/**
	 * AutowiredAnnotationBeanPostProcessor重写的方法
	 * <p>
	 * 解析@Autowired、@Value、@Inject注解，确定要用于给定 bean 的候选构造器。
	 * 该方法仅被AbstractAutowireCapableBeanFactory#determineConstructorsFromBeanPostProcessors方法调用
	 *
	 * @param beanClass bean 的Class，永远不为null
	 * @param beanName  beanName
	 * @return 候选构造器数组，如果没有站到就返回null
	 */
	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		/*
		 * 1 检查查找方法注入，即@Lookup注解，用于原型bean的获取，很少用到
		 * 这部分代码不必关心
		 */
		// Let's check for lookup methods here...
		if (!this.lookupMethodsChecked.contains(beanName)) {
			//确定给定类是否是承载指定注释的候选项（在类型、方法或字段级别）。
			//如果任何一个注解的全路径名都不是以"java."开始，并且该Class全路径名以"start."开始，
			// 或者Class的类型为Ordered.class，那么返回false，否则其他情况都返回true
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						/*
						 * 循环过滤所有的方法（不包括构造器）
						 */
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							//尝试获取方法上的@Lookup注解
							Lookup lookup = method.getAnnotation(Lookup.class);
							//如果存在@Lookup注解
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								//创建一个LookupOverride对象存入当前beanName的mbd中
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									//加入methodOverrides内部的overrides集合中
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						//获取父类Class
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			//检查过的beanName加入到lookupMethodsChecked中，无论有没有@Lookup注解
			this.lookupMethodsChecked.add(beanName);
		}

		// Quick check on the concurrent map first, with minimal locking.
		//已经被推断完成的类和该类被推断出来的构造方法集合map
		/*
		 * 2 查找候选构造器
		 */
		//首先快速检查缓存，看是否已经存在指定类型的候选构造器依赖缓存，即此前是否已解析过
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		/*
		 * 如果缓存为null，说明没解析过，那么需要解析
		 * 解析之后会将该类型Class以及对应的结果加入candidateConstructorsCache缓存，后续同类型再来时不会再次解析
		 */
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			// 加锁防止并发
			synchronized (this.candidateConstructorsCache) {
				//加锁情况下再次获取该类型的缓存
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				/*
				 * 如果缓存为null，说明没解析过，那么需要解析
				 * 如果不为null，那么这一段逻辑就跳过了
				 */
				if (candidateConstructors == null) {
					Constructor<?>[] rawCandidates;
					try {
						//反射获取当前类型的全部构造器数组
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					//candidates用于保存满足条件的候选构造器（存在@Inject注解或者@Autowired注解）
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					//必须的构造器（存在@Inject注解，或者存在@Autowired注解并且required属性为true）
					Constructor<?> requiredConstructor = null;
					//默认的构造器（无参构造器）
					Constructor<?> defaultConstructor = null;
					//对于java类这样永远为null
					//Kotlin专用，Java用不上
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					/*
					 * 循环全部构造器进行解析
					 */
					for (Constructor<?> candidate : rawCandidates) {
						//如果不是合成构造函数，一般都不是
						if (!candidate.isSynthetic()) {
							nonSyntheticConstructors++;
						}
						else if (primaryConstructor != null) {
							continue;
						}
						//拿到构造方法上面的属性，比如@Autowired的属性是required
						//获取构造器上的自动注入注解的MergedAnnotation，一般是按顺序查找@Autowired、@Value、@Inject注解，找到某一个就返回
						//@Value注解不能标注在构造器上，@Inject很少使用，因此构造器大部分都返回@Autowired的MergedAnnotation（如果标注了@Autowired注解）
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						//如果不存在@Autowired、@Inject注解
						if (ann == null) {
							//返回给定类的用户定义的类：通常只是给定类即返回原始类，但在 CGLIB 生成的子类的情况下返回原始类。
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							//内部类或者FactoryBean才会进这个if
							//如果确实是CGLIB子类，那么解析原始类，看原始类上面有没有自动注入的注解
							if (userClass != beanClass) {
								try {
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						//比如加了@Autowired 进入这
						/*
						 * 如果存在@Autowired或者@Inject自动注入注解
						 */
						if (ann != null) {
							//如果requiredConstructor不为null，这是不允许的，抛出异常
							//如果存在使用了@Autowired(required = true)或者@Inject注解的构造器，
							// 那么就不允许其他构造器上出现任何的自动注入注解
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							//@Autowired的状态 默认为true  可以设置false
							//判断是否是@Inject注解，或者是@Autowired注解并且required属性为true，即判断是否是必须的构造器
							boolean required = determineRequiredStatus(ann);
							//如果是必须的构造器
							if (required) {
								//如果candidates不为空，那么同样抛出异常
								//这说明如果存在@Autowired(required = true)或者@Inject注解，那么只能在一个构造器上，且其他构造器上不能出现自动注入注解
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								//那么requiredConstructor等于candidate，即使用了@Autowired(required = true)或者@Inject注解的构造器
								requiredConstructor = candidate;
							}
							//只要存在@Autowired或者@Inject自动注入注解，就会被加入candidates集合
							candidates.add(candidate);
						}
						/*
						 * 否则，表示还是不存在@Autowired或者@Inject自动注入注解
						 *
						 * 判断当前构造器是否是无参构造器，如果是，那么默认构造器就是无参构造器
						 */
						else if (candidate.getParameterCount() == 0) {
							defaultConstructor = candidate;
						}
					}
					/*
					 * 获取最终的candidateConstructors，即候选构造器数组
					 */

					/*
					 * 如果candidates不为空，说明存在具有@Autowired或者@Inject注解的构造器
					 */
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						//如果requiredConstructor为null，即全部都是@Autowired(required = false)的注解
						if (requiredConstructor == null) {
							//如果defaultConstructor不为null，即存在无参构造器
							if (defaultConstructor != null) {
								//那么candidates还要加上无参构造器
								candidates.add(defaultConstructor);
							}
							//否则，如果candidates只有一个元素，即只有一个构造器，那么输出日志
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						//candidates转换为数组，赋给candidateConstructors
						candidateConstructors = candidates.toArray(new Constructor<?>[0]);
					}
					/*
					 * 否则，如果只有一个构造器，并且构造器参数大于0
					 */
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						//新建长度为1的数组，加入该构造器，赋给candidateConstructors
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					//否则，Kotlin专用，不必关心
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					//否则，Kotlin专用
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					//否则，新建空的数组，加入该构造器，赋给candidateConstructors
					else {
						candidateConstructors = new Constructor<?>[0];
					}
					/*
					 * 将对该类型Class及其解析之后的candidateConstructors，存入candidateConstructorsCache缓存，value一定不为null
					 * 后续同类型的Class再来时，不会再次解析
					 */
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		//如果缓存不为null则直接到这一步，或者解析了构造器之后，也会到这一步
		//如果candidateConstructors没有数据，就返回null
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	/**
	 *  * 进一步解析@Autowired、@Value、@Inject注解，执行自动注入
	 * @param pvs the property values that the factory is about to apply (never {@code null})
	 * @param bean the bean instance created, but whose properties have not yet been set
	 * @param beanName the name of the bean
	 * @return
	 */
	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		//拿出前面找到的注入的属性或者方法
		//熟悉的findResourceMetadata方法，用于处理方法或者字段上的@Autowired、@Value、@Inject注解
		//前面的applyMergedBeanDefinitionPostProcessors中已被解析了，因此这里直接从缓存中获取
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			//注入注解的信息
			//调用metadata的inject方法完成注解注入
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		//返回pvs，即原参数
		return pvs;
	}

	@Deprecated
	@Override
	public PropertyValues postProcessPropertyValues(
			PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) {

		return postProcessProperties(pvs, bean, beanName);
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with one of the
	 * configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}

	// * 查找给定类型上的自动注入注解@Autowired、@Value、@Inject
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		//获取缓存key，如果beanName为null或""，那么使用类名
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		//尝试从缓存中获取
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		//如果缓存为null，或者需要刷新，那么解析
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			synchronized (this.injectionMetadataCache) {
				//加锁之后再次获取，防止并发
				metadata = this.injectionMetadataCache.get(cacheKey);
				//如果缓存为null，或者需要刷新，那么解析
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					if (metadata != null) {
						metadata.clear(pvs);
					}
					//创建该类型的AutowiringMetadata
					metadata = buildAutowiringMetadata(clazz);
					//存入缓存
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	private InjectionMetadata buildAutowiringMetadata(final Class<?> clazz) {
		//确定给定类是否是承载指定注释的候选项（在类型、方法或字段级别）。
		//如果任何一个注解的全路径名都不是以"java."开始，并且该Class全路径名以"start."开始，或者Class的类型为Ordered.class，那么返回false，否则其他情况都返回true
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			//如果不满足，那么直接返回空InjectionMetadata对象
			return InjectionMetadata.EMPTY;
		}
		//存储自动注入点的InjectedElement对象集合
		List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		//目标类型
		Class<?> targetClass = clazz;
		/*循环遍历该类及其父类，直到父类为Object或者null*/
		do {
			//当前Class的自动注入点的InjectedElement对象集合
			final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();

			/*
			 * 1 循环过滤所有的字段，查找被自动注入注解（@Autowired、@Value、@Inject）标注的字段注入点
			 * 这三个注解都可以标注在字段上
			 */
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				//findAutowiredAnnotation用于查找自动注入注解，在前在determineCandidateConstructors部分就讲过了
				//按照顺序查找，只要找到一个自动注入注解就返回，因此优先级@Autowired > @Value > @Inject
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				//如果存在自动注入注解
				if (ann != null) {
					//如果字段是静态的，抛出异常
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					//判断是否是必须的，即是否是@Inject、@Value注解，或者是@Autowired注解并且required属性为true
					boolean required = determineRequiredStatus(ann);
					//那么根据当前字段field和required新建一个AutowiredFieldElement，添加到currElements中
					//AutowiredFieldElement表示了一个具有自动注入注解的字段
					currElements.add(new AutowiredFieldElement(field, required));
				}
			});
			/*
			 * 2 循环过滤所有的方法（不包括构造器），查找被自动注入注解（@Autowired、@Value、@Inject）标注的方法和构造器注入点
			 * 注意，标注在参数上是无效的
			 */
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				//查找原始方法而非编译器为我们生成的方法，方法不可见就直接返回
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				//findAutowiredAnnotation用于查找自动注入注解，在前在determineCandidateConstructors部分就讲过了
				//按照顺序查找，只要找到一个自动注入注解就返回，因此优先级@Autowired > @Value > @Inject
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					//如果方法是静态的，抛出异常
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}
					//如果方法参数个数为0，抛出异常
					if (method.getParameterCount() == 0) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					//判断是否是必须的，即是否是@Inject、@Value注解，或者是@Autowired注解并且required属性为true
					boolean required = determineRequiredStatus(ann);
					//查找方法参数
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					//那么根据当前方法method和required和参数新建一个AutowiredMethodElement，添加到currElements中
					//AutowiredMethodElement表示了一个具有自动注入注解的方法
					currElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});
			//currElements集合整体添加到elements集合的开头，即父类的自动注入注解的注入点在前面
			elements.addAll(0, currElements);
			//获取下一个目标类型，是当前类型的父类型
			targetClass = targetClass.getSuperclass();
		}
		//如果目标类型不为null并且不是Object.class类型，那么继续循环，否则结束循环
		while (targetClass != null && targetClass != Object.class);
		//根据找到的elements和Class创建InjectionMetadata对象，如果没有任何注入点元素，那么返回一个空的InjectionMetadata
		return InjectionMetadata.forElements(elements, clazz);
	}

	/**
	 * AutowiredAnnotationBeanPostProcessor的方法
	 * <p>
	 * 获取构造器、方法、字段上的@Autowired、@Value、@Inject注解的MergedAnnotation
	 * 按照顺序查找，因此优先级@Autowired > @Value > @Inject
	 *
	 * @param ao 指定元素，可能是构造器、方法、字段
	 * @return @Autowired、@Value、@Inject注解的MergedAnnotation，没有就返回null
	 */
	@Nullable
	private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		//创建一个新的MergedAnnotations实例，其中包含指定元素中的所有注解和元注解
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		//autowiredAnnotationTypes 包括两个类 Autowired和Value
		//遍历是否存在autowiredAnnotationTypes中的类型的注解：按照顺序为@Autowired、@Value、@Inject
		//autowiredAnnotationTypes中的元素在AutowiredAnnotationBeanPostProcessor初始化时就添加进去了
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			//尝试获取该类型的注解
			MergedAnnotation<?> annotation = annotations.get(type);
			//如果注解存着 直接返回 所以优先级@Autowired > @Value
			//如果存在，那么直接返回该注解的MergedAnnotation
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	@SuppressWarnings({"deprecation", "cast"})
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		// The following (AnnotationAttributes) cast is required on JDK 9+.
		return determineRequiredStatus((AnnotationAttributes)
				ann.asMap(mergedAnnotation -> new AnnotationAttributes(mergedAnnotation.getType())));
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 * @deprecated since 5.2, in favor of {@link #determineRequiredStatus(MergedAnnotation)}
	 */
	@Deprecated
	protected boolean determineRequiredStatus(AnnotationAttributes ann) {
		return (!ann.containsKey(this.requiredParameterName) ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Obtain all beans of the given type as autowire candidates.
	 * @param type the type of the bean
	 * @return the target beans, or an empty Collection if no bean of this type is found
	 * @throws BeansException if bean retrieval failed
	 */
	protected <T> Map<String, T> findAutowireCandidates(Class<T> type) throws BeansException {
		if (this.beanFactory == null) {
			throw new IllegalStateException("No BeanFactory configured - " +
					"override the getBeanOfType method or specify the 'beanFactory' property");
		}
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(this.beanFactory, type);
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	@Nullable
	private Object resolvedCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		//如果cachedArgument不为null
		if (cachedArgument instanceof DependencyDescriptor) {
			DependencyDescriptor descriptor = (DependencyDescriptor) cachedArgument;
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			/*
			 * 调用resolveDependency方法根据类型解析依赖，返回找到的依赖
			 *
			 * 这里的descriptor是此前cachedFieldValue缓存起来的，如果是ShortcutDependencyDescriptor类型，那么
			 * 在resolveDependency方法内部的doResolveDependency方法开头就会尝试调用resolveShortcut方法快速获取依赖，
			 * 而这个方法在DependencyDescriptor中默认返回null，而ShortcutDependencyDescriptor则重写了该方法，
			 * 从给定工厂中快速获取具有指定beanName和type的bean实例。因此，上面的ShortcutDependencyDescriptor用于快速获取依赖项
			 */
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		else {
			//直接返回cachedArgument，当第一次调用的value为null并且不是必须依赖，那么缓存的cachedFieldValue也置空null
			//没有找到依赖又不是必须的，那么直接返回null就行了，同样是提升效率
			return cachedArgument;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 *  * 表示自动注入注解（@Autowired、@Value、@Inject）字段的注入信息的类。
	 */
	private class AutowiredFieldElement extends InjectionMetadata.InjectedElement {
		/**
		 * 是否是必须依赖
		 */
		private final boolean required;
		/**
		 * 是否以缓存，即该注入点是否已被解析过
		 */
		private volatile boolean cached;
		/**
		 * 缓存的已被解析的字段值，可能是DependencyDescriptor或者ShortcutDependencyDescriptor或者null
		 */
		@Nullable
		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null);
			this.required = required;
		}

		/**
		 *      * 完成字段注入点的注入操作，包括@Autowired、@Value、@Inject注解的解析
		 * @param bean
		 * @param beanName
		 * @param pvs
		 * @throws Throwable
		 */
		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			//获取字段
			Field field = (Field) this.member;
			Object value;
			//如果已被缓存过，只要调用过一次该方法，那么cached将被设置为true，后续都走resolvedCachedArgument
			if (this.cached) {
				//从缓存中获取需要注入的依赖
				value = resolvedCachedArgument(beanName, this.cachedFieldValue);
			}
			else {
				//先进这

				//这个类描述我们需要注入的属性
				//获取字段描述符，这里的required属性就是@Autowired注解的required属性值，没有就设置默认true
				DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
				desc.setContainingClass(bean.getClass());
				//自动注入的beanName
				Set<String> autowiredBeanNames = new LinkedHashSet<>(1);
				Assert.state(beanFactory != null, "No BeanFactory available");
				//获取转换器
				TypeConverter typeConverter = beanFactory.getTypeConverter();
				try {
					/*
					 * 调用resolveDependency方法根据类型解析依赖，返回找到的依赖，查找规则在之前讲过，注意这里的required是可以设置的
					 * 如果required设置为false，那么没找到依赖将不会抛出异常
					 * 如果找到多个依赖，那么会尝试查找最合适的依赖，就掉调用determineAutowireCandidate方法，此前就讲过了
					 * 在最后一步会尝试根据name进行匹配，如果还是不能筛选出合适的依赖，那么抛出异常
					 * 这就是byType优先于byName的原理，实际上一个resolveDependency方法就完成了
					 */
					value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
				}
				synchronized (this) {
					//如果没有被缓存，那么尝试将解析结果加入缓存
					if (!this.cached) {
						//如果找到了依赖，或者该依赖是必须的
						if (value != null || this.required) {
							//为该缓存赋值
							this.cachedFieldValue = desc;
							//那么将每一个autowiredBeanName和beanName的依赖关系注册到dependentBeanMap和dependenciesForBeanMap缓存中
							//表示beanName的实例依赖autowiredBeanName的实例，这个方法我们在前面就讲过了
							registerDependentBeans(beanName, autowiredBeanNames);
							//如果只有一个bean，如果字段属性是集合则可能有多个
							if (autowiredBeanNames.size() == 1) {
								//获取name
								String autowiredBeanName = autowiredBeanNames.iterator().next();
								//如果工厂中包含该bean并且类型匹配
								if (beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
									/*
									 * 将当前desc描述符、beanName、字段类型存入一个ShortcutDependencyDescriptor对象中，随后赋给cachedFieldValue
									 * 后续查找时将直接获取就有该beanName和字段类型的依赖实例返回
									 */
									this.cachedFieldValue = new ShortcutDependencyDescriptor(
											desc, autowiredBeanName, field.getType());
								}
							}
						}
						else {
							//如果value为null并且不是必须依赖，那么清空缓存
							//下一次走resolvedCachedArgument方法时，直接返回null
							this.cachedFieldValue = null;
						}
						//cached设置为true，表示已解析过，并且设置了缓存
						this.cached = true;
					}
				}
			}
			if (value != null) {
				//属性注入 bean是X value是属性Y
				ReflectionUtils.makeAccessible(field);
				/*
				 * 反射注入该字段属性的值
				 */
				field.set(bean, value);
			}
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 */
	private class AutowiredMethodElement extends InjectionMetadata.InjectedElement {

		private final boolean required;

		private volatile boolean cached;

		@Nullable
		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			if (checkPropertySkipping(pvs)) {
				return;
			}
			Method method = (Method) this.member;
			Object[] arguments;
			if (this.cached) {
				// Shortcut for avoiding synchronization...
				arguments = resolveCachedArguments(beanName);
			}
			else {
				int argumentCount = method.getParameterCount();
				arguments = new Object[argumentCount];
				DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
				Set<String> autowiredBeans = new LinkedHashSet<>(argumentCount);
				Assert.state(beanFactory != null, "No BeanFactory available");
				TypeConverter typeConverter = beanFactory.getTypeConverter();
				for (int i = 0; i < arguments.length; i++) {
					MethodParameter methodParam = new MethodParameter(method, i);
					DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
					currDesc.setContainingClass(bean.getClass());
					descriptors[i] = currDesc;
					try {
						Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeans, typeConverter);
						if (arg == null && !this.required) {
							arguments = null;
							break;
						}
						arguments[i] = arg;
					}
					catch (BeansException ex) {
						throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
					}
				}
				synchronized (this) {
					if (!this.cached) {
						if (arguments != null) {
							DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, arguments.length);
							registerDependentBeans(beanName, autowiredBeans);
							if (autowiredBeans.size() == argumentCount) {
								Iterator<String> it = autowiredBeans.iterator();
								Class<?>[] paramTypes = method.getParameterTypes();
								for (int i = 0; i < paramTypes.length; i++) {
									String autowiredBeanName = it.next();
									if (beanFactory.containsBean(autowiredBeanName) &&
											beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
										cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
												descriptors[i], autowiredBeanName, paramTypes[i]);
									}
								}
							}
							this.cachedMethodArguments = cachedMethodArguments;
						}
						else {
							this.cachedMethodArguments = null;
						}
						this.cached = true;
					}
				}
			}
			if (arguments != null) {
				try {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		@Nullable
		private Object[] resolveCachedArguments(@Nullable String beanName) {
			Object[] cachedMethodArguments = this.cachedMethodArguments;
			if (cachedMethodArguments == null) {
				return null;
			}
			Object[] arguments = new Object[cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolvedCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		private final Class<?> requiredType;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut, Class<?> requiredType) {
			super(original);
			this.shortcut = shortcut;
			this.requiredType = requiredType;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, this.requiredType);
		}
	}

}
