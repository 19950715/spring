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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other BeanFactoryPostProcessor.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@link BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		PriorityOrdered, ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	/**
	 * A {@code BeanNameGenerator} using fully qualified class names as default bean names.
	 * <p>This default for configuration-level import purposes may be overridden through
	 * {@link #setBeanNameGenerator}. Note that the default for component scanning purposes
	 * is a plain {@link AnnotationBeanNameGenerator#INSTANCE}, unless overridden through
	 * {@link #setBeanNameGenerator} with a unified user-level bean name generator.
	 * @since 5.2
	 * @see #setBeanNameGenerator
	 */
	public static final AnnotationBeanNameGenerator IMPORT_BEAN_NAME_GENERATOR =
			new FullyQualifiedAnnotationBeanNameGenerator();

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";

	/**
	 * Whether this environment lives within a native image.
	 * Exposed as a private static field rather than in a {@code NativeImageDetector.inNativeImage()} static method due to https://github.com/oracle/graal/issues/2594.
	 * @see <a href="https://github.com/oracle/graal/blob/master/sdk/src/org.graalvm.nativeimage/src/org/graalvm/nativeimage/ImageInfo.java">ImageInfo.java</a>
	 */
	private static final boolean IN_NATIVE_IMAGE = (System.getProperty("org.graalvm.nativeimage.imagecode") != null);


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	private boolean localBeanNameGeneratorSet = false;

	/* Using short class names as default bean names by default. */
	private BeanNameGenerator componentScanBeanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	/* Using fully qualified class names as default bean names by default. */
	private BeanNameGenerator importBeanNameGenerator = IMPORT_BEAN_NAME_GENERATOR;


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true;
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}


	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 * 主要功能就是从registry注册表中的已注册的配置类中进一步提取 bean 定义
	 *  @param registry bean定义注册表，实际类型就是当前上下文中的DefaultListableBeanFactory对象
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		//获取容器registry的一个唯一hash值，用于去重判断
		/*
		 * 校验重复处理
		 */
		//获取registry注册表的identityHashCode值作为registryId，实际上就是返回默认hashCode方法的返回值，无论有没有重写hashCode方法
		//一个registry对象有唯一的registryId值
		int registryId = System.identityHashCode(registry);
		//方法postProcessBeanDefinitionRegistry是否己经被调用过了
		//如果registriesPostProcessed缓存包含了该registryId值，那么说明此前该registry已被该ConfigurationClassPostProcessor处理过了
		//Spring不允许同一个ConfigurationClassPostProcessor重复处理同一个registry，直接抛出异常
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		//方法postProcessBeanFactory是否已经调用过了
		//如果factoriesPostProcessed缓存包含了该registryId值，那么说明此前该registry已被该ConfigurationClassPostProcessor处理过了
		//Spring不允许同一个ConfigurationClassPostProcessor重复处理同一个registry，直接抛出异常
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		//容器registry第一次调用方法postProcessBeanDefinitionRegistry
		//将容器registry的唯一hash值放到registriesPostProcessed中
		//当前registry的registryId加入registriesPostProcessed缓存
		this.registriesPostProcessed.add(registryId);
		/*
		 * 继续调用processConfigBeanDefinitions方法，这是核心方法
		 */
		processConfigBeanDefinitions(registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 * 对于被@Configuration注解标注的配置类生成CGLIB代理对象，主要目的是在对bean方法的调用进行代理增强
	 * 最后还会添加一个ImportAwareBeanPostProcessor类型的后处理器
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		//一致性哈希值的校验，类似于postProcessBeanDefinitionRegistry方法
		int factoryId = System.identityHashCode(beanFactory);
		//如果factoriesPostProcessed缓存包含了该factoryId值，那么说明此前该beanFactory已被该ConfigurationClassPostProcessor处理过了
		//Spring不允许同一个ConfigurationClassPostProcessor重复处理同一个beanFactory，直接抛出异常
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		//当前beanFactory的factoryId加入factoriesPostProcessed缓存
		this.factoriesPostProcessed.add(factoryId);
		//如果接口方法postProcessBeanDefinitionRegistry.还没有被调用过
		//这里直接再调用一次
		//如果registriesPostProcessed缓存不包含该factoryId，那么先调用processConfigBeanDefinitions处理所有的配置类
		if (!this.registriesPostProcessed.contains(factoryId)) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
		}
		//1 增强配置类
		enhanceConfigurationClasses(beanFactory);
		//2 添加ImportAwareBeanPostProcessor类型的BeanPostProcessor后处理器
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
		//存放Spring容器中己经注册的，并且符合候选条件配置类的BeanDefinitionHolder
		//临时集合，用于存储配置类的bean定义
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
		//返回此注册表中已注册的所有 bean 定义的名称数组
		String[] candidateNames = registry.getBeanDefinitionNames();
		/*
		 * 1 遍历beanName数组，根据bean定义判断配置类，设置属性，并封装为一个BeanDefinitionHolder加入configCandidates集合
		 */
		for (String beanName : candidateNames) {
			//根据beanName获取对应的bean定义
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			//BeanDefinition中是否存在属性名称为：
			//org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass的值
			/*
			 * 获取bean定义的"org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass"属性
			 * bean定义的父类BeanMetadataAttributeAccessor的方法，如果不为null，说明当前Bean 定义已作为配置类处理过了，这里不再处理
			 */
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			//检查当前的BeanDefinition,也就是beanDef,是否满足配置类的候选条件
			// checkConfigurationClassCandidate()会判断一个是否是一个配置类,并为BeanDefinition设置属性为lite或者full。
			// 在这儿为BeanDefinition设置lite和full属性值是为了后面在使用
			// 如果加了@Configuration，那么对应的BeanDefinition为full;
			// 如果加了@Bean,@Component,@ComponentScan,@Import,@ImportResource这些注解，则为lite。
			//lite和full均表示这个BeanDefinition对应的类是一个配置类
			/*
			 * 否则，调用checkConfigurationClassCandidate校验当前bean定义是否对应着一个配置类，并为其设置相应的属性为lite或者full
			 * 如果具有@Configuration注解以及派生注解，那么算作配置类，设置为full
			 * 如果具有@Bean,@Component,@ComponentScan,@Import,@ImportResource注解及其派生注解之一，那么算作配置类，那么设置为lite
			 * 后面会用到这个属性
			 */
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				//如果BeanDefinition.满足候选条件，就将BeanDefinition封装到BeanDefinitionHolder中
				//同时将BeanDefinitionHolder.添加到集合configCandidates中
				//如果是配置类，则封装成为一个BeanDefinitionHolder加入到configCandidates集合中
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		//如果没有符合条件的BeanDefinition.就直接返回
		/*
		 * 2 如果configCandidates集合没有收集到任何配置类定义，那么直接返回
		 */
		if (configCandidates.isEmpty()) {
			return;
		}

		// Sort by previously determined @Order value, if applicable
		//如果这些BeanDefinition对应的类上添加了@Order注解，就对它f们排序，属性值越小优先级越高
		//到这一步，表示存在配置类

		/*
		 * 3 将configCandidates集合中的配置类按照此前checkConfigurationClassCandidate解析的@Order注解的值排序，值越小越靠前
		 */
		configCandidates.sort((bd1, bd2) -> {
			//获取order值进行比较
			//如果此前没有解析到@Order注解，那么将返回Ordered.LOWEST_PRECEDENCE，即Integer.MAX_VALUE，表示优先级最低，排在末尾
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition());
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition());
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		//看下Spring容器中是否注册了自定义bean名称生成策略的组件
		/*
		 * 确定beanName生成器
		 */
		SingletonBeanRegistry sbr = null;
		//DefaultListableBeanFactory属于SingletonBeanRegistry类型
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			//如果没有自定义的beanName生成器，可通过ConfigurationClassPostProcessor的setBeanNameGenerator方法设置，默认false
			if (!this.localBeanNameGeneratorSet) {
				//Bean名称生成策略的一个组件：（默认为空）
				//获取设置的beanName生成器，一般只有AnnotationConfigApplicationContext上下文的setBeanNameGenerator方法会设置
				//如果是其他上下文容器比如ClassPathXmlApplicationContext就是返回null
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				//如果不为null
				if (generator != null) {
					//那么设置generator为将要使用的beanName生成器
					this.componentScanBeanNameGenerator = generator;
					this.importBeanNameGenerator = generator;
				}
			}
		}

		//初始化环境变量
		//环境变量，在创建该类实例时通过EnvironmentAware自动设置
		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		/*
		 * 4 解析每一个配置类定义
		 */

		/*
		 * 创建配置类解析器，传递：metadataReaderFactory - 元数据解析工厂、problemReporter -问题记录器
		 * environment - 环境变量、resourceLoader - 资源加载器、registry - 注册表，就是当前DefaultListableBeanFactory对象
		 * componentScanBeanNameGenerator - beanName生成器，默认就是AnnotationBeanNameGenerator
		 */
		ConfigurationClassParser parser = new ConfigurationClassParser(
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		//存放符合候选条件的BeanDefinitionHolder
		//保存需要解析的配置类，默认保存configCandidates的中的全部配置类定义
		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
		//存放己经解析完毕的ConfigurationClass(BeanDefinitionHolder解析完毕后，会封装在ConfigurationClass中)
		//保存已被解析的配置类
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size());
		do {
			//解析配置类（包括配置中的各种注解信息）
			/*
			 * 通过parser解析器解析每一个配置类
			 */
			parser.parse(candidates);
			/*
			 * 通过parser解析器验证每一个配置类
			 */
			parser.validate();

			//临时存放candidates,解析完毕后，封装得到的ConfigurationClass集合
			//获取解析后的ConfigurationClass配置类集合
			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses());
			//剔除上一轮while循环中，己经全部解析处理完毕的元素
			//移除全部的alreadyParsed集合中的数据
			configClasses.removeAll(alreadyParsed);

			// Read the model and create bean definitions based on its content
			if (this.reader == null) {
				//创建配置类bean定义阅读器，用于创建BeanDefinition
				this.reader = new ConfigurationClassBeanDefinitionReader(
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}
			//为解析配置类解析后的configClasses,注册新的BeanDefinition
			//主要就是配置类中，标注了注解@Bean的方法需要注册新的BeanDefinition
			/*
			 * 通过reader加载、注册此configClasses中的bean定义，其来源包括
			 * 包括配置类本身、@Bean方法、@ImportedResource注解、@Import注解
			 */
			this.reader.loadBeanDefinitions(configClasses);
			//将解析完毕的configClasses,添加到集合alreadyParsed中
			//将configClasses存入已解析的集合中
			alreadyParsed.addAll(configClasses);
			//清空candidates集合
			candidates.clear();
			//如果Spring容器中的BeanDefinition数量，己经大于之前容器中的数量
			/*
			 * 处理新增了bean定义的情况，将会循环解析新增的bean定义
			 */
			//如果解析之后容器中已注册的bean定义数量大于在解析之前的bean定义数量，说明当前方法向注册表中注册了新的bean定义
			if (registry.getBeanDefinitionCount() > candidateNames.length) {
				//容器中当前己经注册bean的名称集合
				//获取最新的bean定义名集合
				String[] newCandidateNames = registry.getBeanDefinitionNames();
				//方法执行前容器中bean名称集合
				//oldCandidateNames保存一份candidateNames的副本
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames));
				//已解析的bean定义的className集合
				Set<String> alreadyParsedClasses = new HashSet<>();
				//将alreadyParsed中的全部configClass的className加入alreadyParsedClasses集合
				for (ConfigurationClass configurationClass : alreadyParsed) {
					//迄今一共解析完毕了多少个配置类对应的ConfigurationClass
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				//遍历最新bean定义名集合
				for (String candidateName : newCandidateNames) {
					//注册解析配置类过程中，新注册的那些bean的名称
					//比加从添加了注解@Bean上解析来的BeanDefinition
					//如果当前遍历的候选beanName没在旧集合中，说明是新添加的
					if (!oldCandidateNames.contains(candidateName)) {
						//获取当前candidateName的bean定义
						BeanDefinition bd = registry.getBeanDefinition(candidateName);
						//如果当前bean定义是配置类，并且没有被解析过
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {
							//将这些解析过程中，新添加的BeanDefinition
							//作为下一轮while循环中处理的候选类
							//(当然这些@Bean方法得到的类，一般都不可能是配置类，当然，还是有这种可能的)
							//那么存入candidates集合中，此时candidates集合由有了数据，那么将回进行下一次循环，解析新添加的bean定义
							candidates.add(new BeanDefinitionHolder(bd, candidateName));
						}
					}
				}
				//candidateNames设置最新的newCandidateNames集合
				candidateNames = newCandidateNames;
			}
		}
		//如果candidates集合不为空，那么说明在本次解析过程中新添加了bean定义，继续循环解析这些新的bean定义
		while (!candidates.isEmpty());

		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		//如果在解析的过程中，己经解析并注册了名称为
		//org.springframework.context.annotation.ConfigurationClassPostProcessor的单例对象
		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		//如果注册表中不包含名为"org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry"的单例bean实例
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			//从配置类解析器中，获取该单例对象，并注册到单例Spring容器中
			//那么手动注册一个单例bean实例，名为"org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry"
			//实际上就是importStack集合对象
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}
		//默认就是CachingMetadataReaderFactory
		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			//清除缓存
			//清除外部提供的元数据阅读器工厂MetadataReaderFactory中的缓存
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * 查找全部被@Configuration注解标注的配置类，也就是具有"full"属性的配置类，并通过ConfigurationClassEnhancer进行代理增强
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		//需要进行代理增强的配置类集合
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>();
		//遍历全部的BeanDefinition
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			//获取CONFIGURATION_CLASS_ATTRIBUTE属性的值
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE);
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				methodMetadata = ((AnnotatedBeanDefinition) beanDef).getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> resolve bean class at this point...
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) {
					try {
						abd.resolveBeanClass(this.beanClassLoader);
					}
					catch (Throwable ex) {
						throw new IllegalStateException(
								"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
					}
				}
			}
			//校验是否是full模式的Configuration
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				//当前full模式的Configuration 的beanName和bean定义都存入configBeanDefs集合
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		//如果configBeanDefs是空集，那么直接返回，不需要增强
		if (configBeanDefs.isEmpty()) {
			// nothing to enhance -> return immediately
			return;
		}
		if (IN_NATIVE_IMAGE) {
			throw new BeanDefinitionStoreException("@Configuration classes need to be marked as proxyBeanMethods=false. Found: " + configBeanDefs.keySet());
		}

		//新建一个配置类增强器，通过生成CGLIB子类来增强配置类
		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			// If a @Configuration class gets proxied, always proxy the target class
			//新设置一个属性，如果@Configuration已代理，那么始终代理目标类
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// Set enhanced subclass of the user-specified bean class
			Class<?> configClass = beanDef.getBeanClass();
			//设置用户指定的 bean 类的CGLIB 增强子类
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				//设置class为增强类的class，后续创建该配置类的实例时就会创建代理类的实例
				beanDef.setBeanClass(enhancedClass);
			}
		}
	}


	private static class ImportAwareBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		/**
		 * 为EnhancedConfiguration代理对象调用setBeanFactory方法设置beanFactory的属性值
		 */
		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			//对于EnhancedConfiguration类型的代理对象注入beanFactory实例
			if (bean instanceof EnhancedConfiguration) {
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory);
			}
			return pvs;
		}

		/**
		 * 用于ImportAware的setImportMetadata方法回调，设置importMetadata元数据
		 * 主要是支持@Import注解引入的类
		 */
		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			//如果是ImportAware类型
			if (bean instanceof ImportAware) {
				//获取IMPORT_REGISTRY_BEAN_NAME的bean实例，在前面的processConfigBeanDefinitions方法最后就注册了该名字的bean实例
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
				//获取引入当前类的类元数据的最后一个，当前类就是被@Import注解引入的普通类，引入类就是添加@Import注解的类
				AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName());
				if (importingClass != null) {
					//设置到setImportMetadata方法参数中
					((ImportAware) bean).setImportMetadata(importingClass);
				}
			}
			return bean;
		}
	}

}
