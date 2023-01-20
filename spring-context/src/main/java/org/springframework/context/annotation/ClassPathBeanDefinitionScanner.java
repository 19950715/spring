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

package org.springframework.context.annotation;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionDefaults;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.PatternMatchUtils;

/**
 * A bean definition scanner that detects bean candidates on the classpath,
 * registering corresponding bean definitions with a given registry ({@code BeanFactory}
 * or {@code ApplicationContext}).
 *
 * <p>Candidate classes are detected through configurable type filters. The
 * default filters include classes that are annotated with Spring's
 * {@link org.springframework.stereotype.Component @Component},
 * {@link org.springframework.stereotype.Repository @Repository},
 * {@link org.springframework.stereotype.Service @Service}, or
 * {@link org.springframework.stereotype.Controller @Controller} stereotype.
 *
 * <p>Also supports Java EE 6's {@link javax.annotation.ManagedBean} and
 * JSR-330's {@link javax.inject.Named} annotations, if available.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 2.5
 * @see AnnotationConfigApplicationContext#scan
 * @see org.springframework.stereotype.Component
 * @see org.springframework.stereotype.Repository
 * @see org.springframework.stereotype.Service
 * @see org.springframework.stereotype.Controller
 */
public class ClassPathBeanDefinitionScanner extends ClassPathScanningCandidateComponentProvider {

	private final BeanDefinitionRegistry registry;

	private BeanDefinitionDefaults beanDefinitionDefaults = new BeanDefinitionDefaults();

	@Nullable
	private String[] autowireCandidatePatterns;

	private BeanNameGenerator beanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	private ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private boolean includeAnnotationConfig = true;


	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory.
	 * @param registry the {@code BeanFactory} to load bean definitions into, in the form
	 * of a {@code BeanDefinitionRegistry}
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry) {
		this(registry, true);
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory.
	 * <p>If the passed-in bean factory does not only implement the
	 * {@code BeanDefinitionRegistry} interface but also the {@code ResourceLoader}
	 * interface, it will be used as default {@code ResourceLoader} as well. This will
	 * usually be the case for {@link org.springframework.context.ApplicationContext}
	 * implementations.
	 * <p>If given a plain {@code BeanDefinitionRegistry}, the default {@code ResourceLoader}
	 * will be a {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver}.
	 * <p>If the passed-in bean factory also implements {@link EnvironmentCapable} its
	 * environment will be used by this reader.  Otherwise, the reader will initialize and
	 * use a {@link org.springframework.core.env.StandardEnvironment}. All
	 * {@code ApplicationContext} implementations are {@code EnvironmentCapable}, while
	 * normal {@code BeanFactory} implementations are not.
	 * @param registry the {@code BeanFactory} to load bean definitions into, in the form
	 * of a {@code BeanDefinitionRegistry}
	 * @param useDefaultFilters whether to include the default filters for the
	 * {@link org.springframework.stereotype.Component @Component},
	 * {@link org.springframework.stereotype.Repository @Repository},
	 * {@link org.springframework.stereotype.Service @Service}, and
	 * {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 * @see #setResourceLoader
	 * @see #setEnvironment
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters) {
		this(registry, useDefaultFilters, getOrCreateEnvironment(registry));
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory and
	 * using the given {@link Environment} when evaluating bean definition profile metadata.
	 * <p>If the passed-in bean factory does not only implement the {@code
	 * BeanDefinitionRegistry} interface but also the {@link ResourceLoader} interface, it
	 * will be used as default {@code ResourceLoader} as well. This will usually be the
	 * case for {@link org.springframework.context.ApplicationContext} implementations.
	 * <p>If given a plain {@code BeanDefinitionRegistry}, the default {@code ResourceLoader}
	 * will be a {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver}.
	 * @param registry the {@code BeanFactory} to load bean definitions into, in the form
	 * of a {@code BeanDefinitionRegistry}
	 * @param useDefaultFilters whether to include the default filters for the
	 * {@link org.springframework.stereotype.Component @Component},
	 * {@link org.springframework.stereotype.Repository @Repository},
	 * {@link org.springframework.stereotype.Service @Service}, and
	 * {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 * @param environment the Spring {@link Environment} to use when evaluating bean
	 * definition profile metadata
	 * @since 3.1
	 * @see #setResourceLoader
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters,
			Environment environment) {
		/*
		 * 最终调用4个参数的构造器，主要功能是：
		 * 1 注册默认的类型过滤器，尝试添加@Component、@ManagedBean、@Named这三个注解类型过滤器到includeFilters缓存集合中！
		 * 2 加载"META-INF/spring.components"组件索引文件，避免扫描包，提升应用启动速度
		 * 该构造器及其上两个知识点，我们在"IoC容器初始化(3)"文章的createScanner方法中就讲过了，
		 */
		this(registry, useDefaultFilters, environment,
				(registry instanceof ResourceLoader ? (ResourceLoader) registry : null));
	}

	/**
	 * Create a new {@code ClassPathBeanDefinitionScanner} for the given bean factory and
	 * using the given {@link Environment} when evaluating bean definition profile metadata.
	 * @param registry the {@code BeanFactory} to load bean definitions into, in the form
	 * of a {@code BeanDefinitionRegistry}
	 * @param useDefaultFilters whether to include the default filters for the
	 * {@link org.springframework.stereotype.Component @Component},
	 * {@link org.springframework.stereotype.Repository @Repository},
	 * {@link org.springframework.stereotype.Service @Service}, and
	 * {@link org.springframework.stereotype.Controller @Controller} stereotype annotations
	 * @param environment the Spring {@link Environment} to use when evaluating bean
	 * definition profile metadata
	 * @param resourceLoader the {@link ResourceLoader} to use
	 * @since 4.3.6
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, boolean useDefaultFilters,
			Environment environment, @Nullable ResourceLoader resourceLoader) {

		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		this.registry = registry;
		//如果使用默认的filter
		if (useDefaultFilters) {
			//那么注册默认的filter，父类ClassPathScanningCandidateComponentProvider的方法
			registerDefaultFilters();
		}
		//设置相关属性，父类ClassPathScanningCandidateComponentProvider的方法
		setEnvironment(environment);
		/*
		 * 设置 ResourceLoader的时候，会尝试进行"META-INF/spring.components"文件的读取
		 * 委托调用父类ClassPathScanningCandidateComponentProvider的方法
		 */
		setResourceLoader(resourceLoader);
	}


	/**
	 * Return the BeanDefinitionRegistry that this scanner operates on.
	 */
	@Override
	public final BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	/**
	 * Set the defaults to use for detected beans.
	 * @see BeanDefinitionDefaults
	 */
	public void setBeanDefinitionDefaults(@Nullable BeanDefinitionDefaults beanDefinitionDefaults) {
		this.beanDefinitionDefaults =
				(beanDefinitionDefaults != null ? beanDefinitionDefaults : new BeanDefinitionDefaults());
	}

	/**
	 * Return the defaults to use for detected beans (never {@code null}).
	 * @since 4.1
	 */
	public BeanDefinitionDefaults getBeanDefinitionDefaults() {
		return this.beanDefinitionDefaults;
	}

	/**
	 * Set the name-matching patterns for determining autowire candidates.
	 * @param autowireCandidatePatterns the patterns to match against
	 */
	public void setAutowireCandidatePatterns(@Nullable String... autowireCandidatePatterns) {
		this.autowireCandidatePatterns = autowireCandidatePatterns;
	}

	/**
	 * Set the BeanNameGenerator to use for detected bean classes.
	 * <p>Default is a {@link AnnotationBeanNameGenerator}.
	 */
	public void setBeanNameGenerator(@Nullable BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator =
				(beanNameGenerator != null ? beanNameGenerator : AnnotationBeanNameGenerator.INSTANCE);
	}

	/**
	 * Set the ScopeMetadataResolver to use for detected bean classes.
	 * Note that this will override any custom "scopedProxyMode" setting.
	 * <p>The default is an {@link AnnotationScopeMetadataResolver}.
	 * @see #setScopedProxyMode
	 */
	public void setScopeMetadataResolver(@Nullable ScopeMetadataResolver scopeMetadataResolver) {
		this.scopeMetadataResolver =
				(scopeMetadataResolver != null ? scopeMetadataResolver : new AnnotationScopeMetadataResolver());
	}

	/**
	 * Specify the proxy behavior for non-singleton scoped beans.
	 * Note that this will override any custom "scopeMetadataResolver" setting.
	 * <p>The default is {@link ScopedProxyMode#NO}.
	 * @see #setScopeMetadataResolver
	 */
	public void setScopedProxyMode(ScopedProxyMode scopedProxyMode) {
		this.scopeMetadataResolver = new AnnotationScopeMetadataResolver(scopedProxyMode);
	}

	/**
	 * Specify whether to register annotation config post-processors.
	 * <p>The default is to register the post-processors. Turn this off
	 * to be able to ignore the annotations or to process them differently.
	 */
	public void setIncludeAnnotationConfig(boolean includeAnnotationConfig) {
		this.includeAnnotationConfig = includeAnnotationConfig;
	}


	/**
	 * Perform a scan within the specified base packages.
	 * @param basePackages the packages to check for annotated classes
	 * @return number of beans registered
	 */
	public int scan(String... basePackages) {
		//扫描之前容器中的bean定义数量
		int beanCountAtScanStart = this.registry.getBeanDefinitionCount();
		/*
		 * 核心方法，执行扫描，该方法在之前“IoC容器初始化(3)”的文章中就讲过了。
		 * */
		doScan(basePackages);

		// Register annotation config processors, if necessary.
		//如有必要，注册注解配置处理器
		if (this.includeAnnotationConfig) {
			/*
			 * 注册一系列的注解配置后处理器，用于后续创建bean实例过程中对大量相关注解的处理，比如ConfigurationClassPostProcessor
			 * AutowiredAnnotationBeanPostProcessor、CommonAnnotationBeanPostProcessor等重要的后处理器都是在这里注册的
			 *
			 * 在解析< context:component-scan/>、< context:annotation-config/>标签的时候也会调用方法
			 * 这个方法我们在前面的"IoC容器初始化(3)"的文章中就详细说过了
			 */
			AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);
		}
		//返回本次扫描注册的bean定义数量
		return (this.registry.getBeanDefinitionCount() - beanCountAtScanStart);
	}

	/**
	 * Perform a scan within the specified base packages,
	 * returning the registered bean definitions.
	 * <p>This method does <i>not</i> register an annotation config processor
	 * but rather leaves this up to the caller.
	 * @param basePackages the packages to check for annotated classes
	 * @return set of beans registered if any for tooling registration purposes (never {@code null})
	 */
	/**
	 * ClassPathBeanDefinitionScanner的方法
	 * <p>
	 * 在指定的 basePackages包路径数组中执行扫描，创建bean定义并注册到注册表缓存中，最后返回已注册的BeanDefinitionHolder集合
	 * 不会注册任何一个AnnotationConfigProcessor，而是把这留给调用方去注册，即后面的registerComponents方法
	 *
	 * @param basePackages 要扫描的包路径数组
	 * @return 扫描到的BeanDefinitionHolder集合
	 */
	protected Set<BeanDefinitionHolder> doScan(String... basePackages) {
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		Set<BeanDefinitionHolder> beanDefinitions = new LinkedHashSet<>();
		for (String basePackage : basePackages) {
			//在包路径basePackage下，加载所有符合候选条件类的BeanDefinition
			/*
			 * 1 扫描包路径，找出全部符合过滤器要求的BeanDefinition
			 * 调用的父类ClassPathScanningCandidateComponentProvider的方法
			 * 返回的BeanDefinition的实际类型为ScannedGenericBeanDefinition
			 */
			Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
			for (BeanDefinition candidate : candidates) {
				//获取BeanDefinition中，注解@Scope中的元数据信息
				/*如果存在，则解析@Scope注解，为候选bean设置代理的方式ScopedProxyMode，XML属性也能配置：scope-resolver、scoped-proxy*/
				ScopeMetadata scopeMetadata = this.scopeMetadataResolver.resolveScopeMetadata(candidate);
				//注解@Scope.默认的作用域为单例：singleton
				candidate.setScope(scopeMetadata.getScopeName());
				/*
				 * 2 使用beanName生成器beanNameGenerator来生成beanName
				 * 这里的beanNameGenerator是AnnotationBeanNameGenerator类型的实例，而前面讲的DefaultBeanNameGenerator类型的生成器
				 * 则是基于XML的配置的bean使用的，它们的beanName生成规则不一样
				 * AnnotationBeanNameGenerator相比于DefaultBeanNameGenerator出现的更晚，现在基本上都是注解配置，因此AnnotationBeanNameGenerator用得更多
				 */
				String beanName = this.beanNameGenerator.generateBeanName(candidate, this.registry);
				//如果bean定义是AbstractBeanDefinition类型，ScannedGenericBeanDefinition属于AbstractBeanDefinition类型
				if (candidate instanceof AbstractBeanDefinition) {
					//为BeanDefinition注册一些默认的属性值
					/*
					 * 3 将进一步设置应用于给定的BeanDefinition，使用AbstractBeanDefinition的一些默认属性值
					 * 设置autowireCandidate属性，即XML的autowire-candidate属性，IoC学习的时候就见过该属性，默认为true，表示该bean支持成为自动注入候选bean
					 */
					postProcessBeanDefinition((AbstractBeanDefinition) candidate, beanName);
				}
				//BeanDefinition是否是AnnotatedBeanDefinition的实例，也就是注解类型的BeanDefinition
				//如果bean定义是AnnotatedBeanDefinition类型，ScannedGenericBeanDefinition同样属于AnnotatedBeanDefinition类型
				if (candidate instanceof AnnotatedBeanDefinition) {
					//解析各种其他注解@Lazy、@Primary、@DependsOn、@Role以及@Description。将其他各种注解上的信息，设置到BeanDefinition中
					/*
					 * 4 处理类上的其他通用注解：@Lazy, @Primary, @DependsOn, @Role, @Description
					 */
					AnnotationConfigUtils.processCommonDefinitionAnnotations((AnnotatedBeanDefinition) candidate);
				}
				//判断当前的candidate,和容器中己经注册的BeanDefinition是否兼容
				/*
				 * 5 检查给定的 beanName，确定相应的bean 定义是否需要注册或与现有bean定义兼容
				 *
				 * 如果已注册同名的bean定义，并且它们的bean定义不兼容，那么直接抛出异常
				 * 通常，如果我们为两个注解标注的类指定同一个beanName的时候就能复现这个异常
				 */
				if (checkCandidate(beanName, candidate)) {
					//将BeanDefinition beanName封装到BeanDefinitionHolder中
					//如果可以注册，则将当前beanName以及BeanDefinition封装成为BeanDefinitionHolder对象
					BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(candidate, beanName);
					//根据proxyMode属性的值，判断是否需要创建scope代理，一般都是不需要的
					definitionHolder =
							AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
					beanDefinitions.add(definitionHolder);
					//注册BeanDefinition到Spring容器中
					/*
					 * 6 注册definitionHolder
					 * 之前已经讲过该方法，即尝试注册到beanDefinitionMap、beanDefinitionNames、aliasMap这三个缓存中
					 */
					registerBeanDefinition(definitionHolder, this.registry);
				}
			}
		}
		return beanDefinitions;
	}

	/**
	 * Apply further settings to the given bean definition,
	 * beyond the contents retrieved from scanning the component class.
	 * @param beanDefinition the scanned bean definition
	 * @param beanName the generated bean name for the given bean
	 */
	protected void postProcessBeanDefinition(AbstractBeanDefinition beanDefinition, String beanName) {
		beanDefinition.applyDefaults(this.beanDefinitionDefaults);
		if (this.autowireCandidatePatterns != null) {
			beanDefinition.setAutowireCandidate(PatternMatchUtils.simpleMatch(this.autowireCandidatePatterns, beanName));
		}
	}

	/**
	 * Register the specified bean with the given registry.
	 * <p>Can be overridden in subclasses, e.g. to adapt the registration
	 * process or to register further bean definitions for each scanned bean.
	 * @param definitionHolder the bean definition plus bean name for the bean
	 * @param registry the BeanDefinitionRegistry to register the bean with
	 */
	protected void registerBeanDefinition(BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry) {
		BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
	}


	/**
	 * Check the given candidate's bean name, determining whether the corresponding
	 * bean definition needs to be registered or conflicts with an existing definition.
	 * @param beanName the suggested name for the bean
	 * @param beanDefinition the corresponding bean definition
	 * @return {@code true} if the bean can be registered as-is;
	 * {@code false} if it should be skipped because there is an
	 * existing, compatible bean definition for the specified name
	 * @throws ConflictingBeanDefinitionException if an existing, incompatible
	 * bean definition has been found for the specified name
	 */
	protected boolean checkCandidate(String beanName, BeanDefinition beanDefinition) throws IllegalStateException {
		//如果注册表的beanDefinitionMap缓存中还没有该beanName的缓存，那么返回true
		if (!this.registry.containsBeanDefinition(beanName)) {
			return true;
		}
		//否则，从beanDefinitionMap缓存中获取已存在的同名beanName的bean定义
		BeanDefinition existingDef = this.registry.getBeanDefinition(beanName);
		//获取原始的BeanDefinition
		BeanDefinition originatingDef = existingDef.getOriginatingBeanDefinition();
		//如果原始的BeanDefinition不为null
		if (originatingDef != null) {
			//那么使用原始的BeanDefinition来比较
			existingDef = originatingDef;
		}
		//检查当前的beanDefinition与原始的existingDef是否兼容，如果兼容则直接返回false，不需要继续注册了
		//当现有beanDefinition和原始的beanDefinition来自同一源source或非扫描源non-scanning source时，默认实现将它们视为兼容
		if (isCompatible(beanDefinition, existingDef)) {
			return false;
		}
		/*
		 * 不兼容就直接抛出异常了，这里也就是使用@Component等组件注解标注bean的不能有重名的逻辑了
		 * 但是同样这里没有校验通过组件注解标注的普通内部类的beanName，也就是说如果普通内部类和其他类（比如外部类、静态内部类）设置了相同的beanName
		 * 那么将会造成bean定义的覆盖，而不会抛出遗产，但是使用时由于使用者不知道bean定义被覆盖了可能导致类型异常，因此一定要注意最好手动避免重名的bean定义
		 */
		throw new ConflictingBeanDefinitionException("Annotation-specified bean name '" + beanName +
				"' for bean class [" + beanDefinition.getBeanClassName() + "] conflicts with existing, " +
				"non-compatible bean definition of same name and class [" + existingDef.getBeanClassName() + "]");
	}

	/**
	 * Determine whether the given new bean definition is compatible with
	 * the given existing bean definition.
	 * <p>The default implementation considers them as compatible when the existing
	 * bean definition comes from the same source or from a non-scanning source.
	 * @param newDefinition the new bean definition, originated from scanning
	 * @param existingDefinition the existing bean definition, potentially an
	 * explicitly defined one or a previously generated one from scanning
	 * @return whether the definitions are considered as compatible, with the
	 * new definition to be skipped in favor of the existing definition
	 */
	protected boolean isCompatible(BeanDefinition newDefinition, BeanDefinition existingDefinition) {
		return (!(existingDefinition instanceof ScannedGenericBeanDefinition) ||  // explicitly registered overriding bean
				(newDefinition.getSource() != null && newDefinition.getSource().equals(existingDefinition.getSource())) ||  // scanned same file twice
				newDefinition.equals(existingDefinition));  // scanned equivalent class twice
	}


	/**
	 * Get the Environment from the given registry if possible, otherwise return a new
	 * StandardEnvironment.
	 */
	private static Environment getOrCreateEnvironment(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		if (registry instanceof EnvironmentCapable) {
			return ((EnvironmentCapable) registry).getEnvironment();
		}
		return new StandardEnvironment();
	}

}
