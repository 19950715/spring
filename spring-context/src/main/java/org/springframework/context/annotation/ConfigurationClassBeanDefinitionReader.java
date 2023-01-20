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

package org.springframework.context.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.SpringProperties;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Reads a given fully-populated set of ConfigurationClass instances, registering bean
 * definitions with the given {@link BeanDefinitionRegistry} based on its contents.
 *
 * <p>This class was modeled after the {@link BeanDefinitionReader} hierarchy, but does
 * not implement/extend any of its artifacts as a set of configuration classes is not a
 * {@link Resource}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @since 3.0
 * @see ConfigurationClassParser
 */
class ConfigurationClassBeanDefinitionReader {

	private static final Log logger = LogFactory.getLog(ConfigurationClassBeanDefinitionReader.class);

	private static final ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	/**
	 * Boolean flag controlled by a {@code spring.xml.ignore} system property that instructs Spring to
	 * ignore XML, i.e. to not initialize the XML-related infrastructure.
	 * <p>The default is "false".
	 */
	private static final boolean shouldIgnoreXml = SpringProperties.getFlag("spring.xml.ignore");

	private final BeanDefinitionRegistry registry;

	private final SourceExtractor sourceExtractor;

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanNameGenerator importBeanNameGenerator;

	private final ImportRegistry importRegistry;

	private final ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@link ConfigurationClassBeanDefinitionReader} instance
	 * that will be used to populate the given {@link BeanDefinitionRegistry}.
	 */
	ConfigurationClassBeanDefinitionReader(BeanDefinitionRegistry registry, SourceExtractor sourceExtractor,
			ResourceLoader resourceLoader, Environment environment, BeanNameGenerator importBeanNameGenerator,
			ImportRegistry importRegistry) {

		this.registry = registry;
		this.sourceExtractor = sourceExtractor;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.importBeanNameGenerator = importBeanNameGenerator;
		this.importRegistry = importRegistry;
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	/**
	 * Read {@code configurationModel}, registering bean definitions
	 * with the registry based on its contents.
	 */
	public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
		//评估@Conditional注释，判断当前配置类的解析否需要跳过，将会跟踪结果并考虑到"importBy"。
		TrackedConditionEvaluator trackedConditionEvaluator = new TrackedConditionEvaluator();
		//遍历解析后的ConfigurationClass配置类集合，加载bean定义
		for (ConfigurationClass configClass : configurationModel) {
			//从ConfigurationClass加载bean定义
			loadBeanDefinitionsForConfigurationClass(configClass, trackedConditionEvaluator);
		}
	}

	/**
	 * Read a particular {@link ConfigurationClass}, registering bean definitions
	 * for the class itself and all of its {@link Bean} methods.
	 *  * 读取特定的ConfigurationClass配置类，注册该类本身及其所有@Bean方法的 bean 定义
	 *
	 *  通过reader加载、注册此configClasses中的bean定义，主要有一下4个加载的逻辑：
	 *
	 * 1。如果当前配置类是被引入的，通过@Import注解、处理内部类等方式找到的configClass，都算作被引入的。
	 * 那么解析注册配置类本身成为AnnotatedGenericBeanDefinition类型的bean定义，并且注册到注册表中。在这里，就会对非静态内部配置类进行注册。
	 *
	 * 2。解析@Bean方法成为ConfigurationClassBeanDefinition类型的bean定义，并且注册到注册表中。
	 *
	 * 3。加载、解析@ImportedResource注解引入的XML配置文件中的bean定义到注册表中。核心还是BeanDefinitionReader.loadBeanDefinitions方法加载resource资源，
	 * 解析、注册bean定义，这个方法我们在"IoC容器初始化(2)"的文章中就详细讲过了。
	 *
	 * 4。对于@Import注解引入的ImportBeanDefinitionRegistrar类型的对象的registerBeanDefinitions方法进行统一回调。
	 * 该方法可用于自定义的注册、修改bean定义，因为它将注册表作为参数。
	 */
	private void loadBeanDefinitionsForConfigurationClass(
			ConfigurationClass configClass, TrackedConditionEvaluator trackedConditionEvaluator) {
		//如果trackedConditionEvaluator的shouldSkip方法返回true，即应该跳过
		if (trackedConditionEvaluator.shouldSkip(configClass)) {
			//获取beanName
			String beanName = configClass.getBeanName();
			//如果存在beanName，并且注册表中已经包含了该beanName的bean定义
			if (StringUtils.hasLength(beanName) && this.registry.containsBeanDefinition(beanName)) {
				/*
				 * 从注册表中移除该beanName的bean定义
				 * 因此，此前加入进来的配置类的bean定义将可能被移除
				 */
				this.registry.removeBeanDefinition(beanName);
			}
			//移除importRegistry即importStack中的缓存
			this.importRegistry.removeImportingClass(configClass.getMetadata().getClassName());
			return;
		}

		//@Import进来的类，和内部类走这里变成BeanDefinition
		//如果当前配置类是被引入的，通过@Import注解、处理内部类等方式找到的configClass，都算作被引入的
		if (configClass.isImported()) {
			/*
			 * 1 解析注册配置类本身成为AnnotatedGenericBeanDefinition类型的bean定义，并且注册到注册表中。
			 * 在这里，就会对非静态内部配置类进行注册。
			 */
			registerBeanDefinitionForImportedConfigurationClass(configClass);
		}
		//遍历处理注解配置类中的每个方法 进这
		// @Bean注解的方法变成BeanDefinition
		//获取全部的@Bean方法，遍历
		for (BeanMethod beanMethod : configClass.getBeanMethods()) {
			/*
			 * 2 解析@Bean方法成为ConfigurationClassBeanDefinition类型的bean定义，并且注册到注册表中。
			 */
			loadBeanDefinitionsForBeanMethod(beanMethod);
		}
		/*
		 * 3 加载、解析@ImportedResource注解引入的XML配置文件中的bean定义到注册表中。
		 * 核心还是BeanDefinitionReader的loadBeanDefinitions方法加载resource资源，解析、注册bean定义
		 * 这个方法我们在"IoC容器初始化(2)"的文章中就详细讲过了
		 */
		loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());
		/*
		 * 4 @Import注解引入的ImportBeanDefinitionRegistrar类型的对象的registerBeanDefinitions方法的回调。
		 * 该方法可用于自定义的注册、修改bean定义，因为它将注册表作为参数
		 */
		loadBeanDefinitionsFromRegistrars(configClass.getImportBeanDefinitionRegistrars());
	}

	/**
	 * Register the {@link Configuration} class itself as a bean definition.
	 *  * 将配置类本身注册为 bean 定义，这里就是对非静态内部配置类进行注册的地方
	 */
	private void registerBeanDefinitionForImportedConfigurationClass(ConfigurationClass configClass) {
		AnnotationMetadata metadata = configClass.getMetadata();
		//创建AnnotatedGenericBeanDefinition类型的bean定义
		AnnotatedGenericBeanDefinition configBeanDef = new AnnotatedGenericBeanDefinition(metadata);

		ScopeMetadata scopeMetadata = scopeMetadataResolver.resolveScopeMetadata(configBeanDef);
		configBeanDef.setScope(scopeMetadata.getScopeName());
		//查找或者生成beanName，采用的生成器是FullyQualifiedAnnotationBeanNameGenerator
		//它继承了AnnotationBeanNameGenerator，区别就在于如果没指定beanName那么自己的beanName生成规则是直接以全路径类名作为beanName
		String configBeanName = this.importBeanNameGenerator.generateBeanName(configBeanDef, this.registry);
		//处理类上的其他通用注解：@Lazy, @Primary, @DependsOn, @Role, @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(configBeanDef, metadata);
		//封装成为BeanDefinitionHolder对象
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(configBeanDef, configBeanName);
		//根据proxyMode属性的值，判断是否需要创建scope代理，一般都是不需要的
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		//调用registerBeanDefinition方法注册BeanDefinition到注册表的缓存中，该方法此前已经讲过了
		this.registry.registerBeanDefinition(definitionHolder.getBeanName(), definitionHolder.getBeanDefinition());
		//设置beanName
		configClass.setBeanName(configBeanName);

		if (logger.isTraceEnabled()) {
			logger.trace("Registered bean definition for imported class '" + configBeanName + "'");
		}
	}

	/**
	 * Read the given {@link BeanMethod}, registering bean definitions
	 * with the BeanDefinitionRegistry based on its contents.
	 * 根据给定的BeanMethod解析为bean定义像注册表中注册
	 */
	@SuppressWarnings("deprecation")  // for RequiredAnnotationBeanPostProcessor.SKIP_REQUIRED_CHECK_ATTRIBUTE
	private void loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod) {
		//获取方法所属的类
		ConfigurationClass configClass = beanMethod.getConfigurationClass();
		//获取方法上的元数据信息
		MethodMetadata metadata = beanMethod.getMetadata();
		//获取方法名
		String methodName = metadata.getMethodName();

		// Do we need to mark the bean as skipped by its condition?
		/*
		 * 处理方法上的@Conditional注解，判断是否应该跳过此方法的处理
		 * 这里的metadata就是方法元数据，MethodMetadata
		 */
		//如果shouldSkip返回true，即当前@Bean的方法应该跳过解析，这里的phase生效的阶段参数为REGISTER_BEAN
		if (this.conditionEvaluator.shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN)) {
			//那么加入到当前配置类的skippedBeanMethods缓存中
			configClass.skippedBeanMethods.add(methodName);
			return;
		}
		//如果此前就解析了该方法，并且应该跳过，那么直接返回
		if (configClass.skippedBeanMethods.contains(methodName)) {
			return;
		}

		//获取方法元数据信总息中的注解@Bean的属性信息
		//获取@Bean注解的属性集合
		AnnotationAttributes bean = AnnotationConfigUtils.attributesFor(metadata, Bean.class);
		Assert.state(bean != null, "No @Bean annotation attributes");

		// Consider name and any aliases
		/*
		 * 考虑名字和别名
		 */
		//获取name属性集合
		List<String> names = new ArrayList<>(Arrays.asList(bean.getStringArray("name")));
		/*
		 * 获取beanName。如果设置了name属性，那么将第一个值作为beanName，其他的值作为别名，否则直接将方法名作为beanName
		 */
		String beanName = (!names.isEmpty() ? names.remove(0) : methodName);

		// Register aliases even when overridden
		for (String alias : names) {
			/*
			 * 注册别名映射，registerAlias方法我们在此前"IoC容器初始化(3)"的文章中已经讲过了
			 * 将是将别名alias和名字beanName的映射注册到SimpleAliasRegistry注册表的aliasMap缓存汇总
			 */
			this.registry.registerAlias(beanName, alias);
		}

		// Has this effectively been overridden before (e.g. via XML)?
		/*
		 * 校验是否存在同名的bean定义，以及是否允许同名的bean定义覆盖
		 */
		if (isOverriddenByExistingDefinition(beanMethod, beanName)) {
			//如果返回true，并且如果beanName就等于当前bean方法所属的类的beanName，那么抛出异常
			if (beanName.equals(beanMethod.getConfigurationClass().getBeanName())) {
				throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
						beanName, "Bean name derived from @Bean method '" + beanMethod.getMetadata().getMethodName() +
						"' clashes with bean name for containing configuration class; please make those names unique!");
			}
			//如果返回true，直接返回，当前bean方法不再解析
			return;
		}

		//创建ConfigurationClassBeanDefinition来封装方法的BeanDefinition
		//新建一个ConfigurationClassBeanDefinition类型的bean定义，从这里可知@Bean方法的bean定义的类型
		ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass, metadata);
		beanDef.setSource(this.sourceExtractor.extractSource(metadata, configClass.getResource()));
		//如果当前bean方法是静态的
		if (metadata.isStatic()) {
			// static @Bean method
			//看作静态工厂方法
			if (configClass.getMetadata() instanceof StandardAnnotationMetadata) {
				beanDef.setBeanClass(((StandardAnnotationMetadata) configClass.getMetadata()).getIntrospectedClass());
			}
			else {
				beanDef.setBeanClassName(configClass.getMetadata().getClassName());
			}
			//设置工厂方法名
			beanDef.setUniqueFactoryMethodName(methodName);
		}
		else {
			// instance @Bean method
			//看作实例工厂方法
			beanDef.setFactoryBeanName(configClass.getBeanName());
			beanDef.setUniqueFactoryMethodName(methodName);
		}
		//设置解析的工厂方法
		if (metadata instanceof StandardMethodMetadata) {
			beanDef.setResolvedFactoryMethod(((StandardMethodMetadata) metadata).getIntrospectedMethod());
		}
		//设置自动装配模式为构造器自动注入
		beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		//设置属性
		beanDef.setAttribute(org.springframework.beans.factory.annotation.RequiredAnnotationBeanPostProcessor.
				SKIP_REQUIRED_CHECK_ATTRIBUTE, Boolean.TRUE);
		//处理方法上的其他通用注解：@Lazy, @Primary, @DependsOn, @Role, @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDef, metadata);
		//获取autowire属性，默认Autowire.NO，即不自动注入
		Autowire autowire = bean.getEnum("autowire");
		//设置自动注入模式
		if (autowire.isAutowire()) {
			beanDef.setAutowireMode(autowire.value());
		}
		//设置autowireCandidate属性，默认tue
		boolean autowireCandidate = bean.getBoolean("autowireCandidate");
		if (!autowireCandidate) {
			beanDef.setAutowireCandidate(false);
		}
		//设置initMethodName属性
		String initMethodName = bean.getString("initMethod");
		if (StringUtils.hasText(initMethodName)) {
			beanDef.setInitMethodName(initMethodName);
		}
		//设置destroyMethod属性
		String destroyMethodName = bean.getString("destroyMethod");
		beanDef.setDestroyMethodName(destroyMethodName);

		// Consider scoping
		//考虑作用域和代理
		ScopedProxyMode proxyMode = ScopedProxyMode.NO;
		AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(metadata, Scope.class);
		if (attributes != null) {
			//设置作用域
			beanDef.setScope(attributes.getString("value"));
			//获取作用域代理属性，默认不使用代理
			proxyMode = attributes.getEnum("proxyMode");
			if (proxyMode == ScopedProxyMode.DEFAULT) {
				proxyMode = ScopedProxyMode.NO;
			}
		}

		// Replace the original bean definition with the target one, if necessary
		//如有必要，将原始 bean 定义替换为代理目标定义
		BeanDefinition beanDefToRegister = beanDef;
		if (proxyMode != ScopedProxyMode.NO) {
			BeanDefinitionHolder proxyDef = ScopedProxyCreator.createScopedProxy(
					new BeanDefinitionHolder(beanDef, beanName), this.registry,
					proxyMode == ScopedProxyMode.TARGET_CLASS);
			beanDefToRegister = new ConfigurationClassBeanDefinition(
					(RootBeanDefinition) proxyDef.getBeanDefinition(), configClass, metadata);
		}

		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Registering bean definition for @Bean method %s.%s()",
					configClass.getMetadata().getClassName(), beanName));
		}
		//将方法对应BeanDefinition注册到Spring容器中
		//调用registerBeanDefinition方法注册BeanDefinition到注册表的缓存中，该方法此前已经讲过了
		this.registry.registerBeanDefinition(beanName, beanDefToRegister);
	}

	protected boolean isOverriddenByExistingDefinition(BeanMethod beanMethod, String beanName) {
		if (!this.registry.containsBeanDefinition(beanName)) {
			return false;
		}
		BeanDefinition existingBeanDef = this.registry.getBeanDefinition(beanName);

		// Is the existing bean definition one that was created from a configuration class?
		// -> allow the current bean method to override, since both are at second-pass level.
		// However, if the bean method is an overloaded case on the same configuration class,
		// preserve the existing bean definition.
		if (existingBeanDef instanceof ConfigurationClassBeanDefinition) {
			ConfigurationClassBeanDefinition ccbd = (ConfigurationClassBeanDefinition) existingBeanDef;
			if (ccbd.getMetadata().getClassName().equals(
					beanMethod.getConfigurationClass().getMetadata().getClassName())) {
				if (ccbd.getFactoryMethodMetadata().getMethodName().equals(ccbd.getFactoryMethodName())) {
					ccbd.setNonUniqueFactoryMethodName(ccbd.getFactoryMethodMetadata().getMethodName());
				}
				return true;
			}
			else {
				return false;
			}
		}

		// A bean definition resulting from a component scan can be silently overridden
		// by an @Bean method, as of 4.2...
		if (existingBeanDef instanceof ScannedGenericBeanDefinition) {
			return false;
		}

		// Has the existing bean definition bean marked as a framework-generated bean?
		// -> allow the current bean method to override it, since it is application-level
		if (existingBeanDef.getRole() > BeanDefinition.ROLE_APPLICATION) {
			return false;
		}

		// At this point, it's a top-level override (probably XML), just having been parsed
		// before configuration class processing kicks in...
		if (this.registry instanceof DefaultListableBeanFactory &&
				!((DefaultListableBeanFactory) this.registry).isAllowBeanDefinitionOverriding()) {
			throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
					beanName, "@Bean definition illegally overridden by existing bean definition: " + existingBeanDef);
		}
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Skipping bean definition for %s: a definition for bean '%s' " +
					"already exists. This top-level bean definition is considered as an override.",
					beanMethod, beanName));
		}
		return true;
	}

	// * 加载、解析@ImportedResource注解引入的XML配置文件中的bean定义到注册表中。
	private void loadBeanDefinitionsFromImportedResources(
			Map<String, Class<? extends BeanDefinitionReader>> importedResources) {
		//bean定义读取器的缓存
		Map<Class<?>, BeanDefinitionReader> readerInstanceCache = new HashMap<>();
		/*循环加载*/
		importedResources.forEach((resource, readerClass) -> {
			// Default reader selection necessary?
			if (BeanDefinitionReader.class == readerClass) {
				//支持 Groovy 语言
				if (StringUtils.endsWithIgnoreCase(resource, ".groovy")) {
					// When clearly asking for Groovy, that's what they'll get...
					readerClass = GroovyBeanDefinitionReader.class;
				}
				else if (shouldIgnoreXml) {
					throw new UnsupportedOperationException("XML support disabled");
				}
				else {
					// Primarily ".xml" files but for any other extension as well
					readerClass = XmlBeanDefinitionReader.class;
				}
			}

			BeanDefinitionReader reader = readerInstanceCache.get(readerClass);
			//如果reader为null，那么新建reader
			if (reader == null) {
				try {
					// Instantiate the specified BeanDefinitionReader
					reader = readerClass.getConstructor(BeanDefinitionRegistry.class).newInstance(this.registry);
					// Delegate the current ResourceLoader to it if possible
					if (reader instanceof AbstractBeanDefinitionReader) {
						AbstractBeanDefinitionReader abdr = ((AbstractBeanDefinitionReader) reader);
						abdr.setResourceLoader(this.resourceLoader);
						abdr.setEnvironment(this.environment);
					}
					readerInstanceCache.put(readerClass, reader);
				}
				catch (Throwable ex) {
					throw new IllegalStateException(
							"Could not instantiate BeanDefinitionReader class [" + readerClass.getName() + "]");
				}
			}

			// TODO SPR-6310: qualify relative path locations as done in AbstractContextLoader.modifyLocations
			//最终调用reader的loadBeanDefinitions方法加载resource资源，解析、注册bean定义
			//这个方法我们在"IoC容器初始化(2)"的文章中就详细讲过了
			reader.loadBeanDefinitions(resource);
		});
	}

	private void loadBeanDefinitionsFromRegistrars(Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> registrars) {
		//循环importBeanDefinitionRegistrars缓存map，回调每一个ImportBeanDefinitionRegistrar对象的三个参数的registerBeanDefinitions方法
		registrars.forEach((registrar, metadata) ->
				registrar.registerBeanDefinitions(metadata, this.registry, this.importBeanNameGenerator));
	}


	/**
	 * {@link RootBeanDefinition} marker subclass used to signify that a bean definition
	 * was created from a configuration class as opposed to any other configuration source.
	 * Used in bean overriding cases where it's necessary to determine whether the bean
	 * definition was created externally.
	 */
	@SuppressWarnings("serial")
	private static class ConfigurationClassBeanDefinition extends RootBeanDefinition implements AnnotatedBeanDefinition {

		private final AnnotationMetadata annotationMetadata;

		private final MethodMetadata factoryMethodMetadata;

		public ConfigurationClassBeanDefinition(ConfigurationClass configClass, MethodMetadata beanMethodMetadata) {
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
			setResource(configClass.getResource());
			setLenientConstructorResolution(false);
		}

		public ConfigurationClassBeanDefinition(
				RootBeanDefinition original, ConfigurationClass configClass, MethodMetadata beanMethodMetadata) {
			super(original);
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
		}

		private ConfigurationClassBeanDefinition(ConfigurationClassBeanDefinition original) {
			super(original);
			this.annotationMetadata = original.annotationMetadata;
			this.factoryMethodMetadata = original.factoryMethodMetadata;
		}

		@Override
		public AnnotationMetadata getMetadata() {
			return this.annotationMetadata;
		}

		@Override
		@NonNull
		public MethodMetadata getFactoryMethodMetadata() {
			return this.factoryMethodMetadata;
		}

		@Override
		public boolean isFactoryMethod(Method candidate) {
			return (super.isFactoryMethod(candidate) && BeanAnnotationHelper.isBeanAnnotated(candidate));
		}

		@Override
		public ConfigurationClassBeanDefinition cloneBeanDefinition() {
			return new ConfigurationClassBeanDefinition(this);
		}
	}


	/**
	 * Evaluate {@code @Conditional} annotations, tracking results and taking into
	 * account 'imported by'.
	 */
	private class TrackedConditionEvaluator {

		private final Map<ConfigurationClass, Boolean> skipped = new HashMap<>();

		public boolean shouldSkip(ConfigurationClass configClass) {
			Boolean skip = this.skipped.get(configClass);
			if (skip == null) {
				if (configClass.isImported()) {
					boolean allSkipped = true;
					for (ConfigurationClass importedBy : configClass.getImportedBy()) {
						if (!shouldSkip(importedBy)) {
							allSkipped = false;
							break;
						}
					}
					if (allSkipped) {
						// The config classes that imported this one were all skipped, therefore we are skipped...
						skip = true;
					}
				}
				if (skip == null) {
					skip = conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN);
				}
				this.skipped.put(configClass, skip);
			}
			return skip;
		}
	}

}
