/*
 * Copyright 2002-2018 the original author or authors.
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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 */
class ComponentScanAnnotationParser {

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanNameGenerator beanNameGenerator;

	private final BeanDefinitionRegistry registry;


	public ComponentScanAnnotationParser(Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.beanNameGenerator = beanNameGenerator;
		this.registry = registry;
	}

	// * 解析@ComponentScan注解，获取解析到的bean定义集合
	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, final String declaringClass) {
		/*
		 * 创建一个类路径Bean定义扫描器
		 * 通过useDefaultFilters属性的值判断是否注册默认的类型过滤器，默认值true，即注册默认类型过滤器
		 *
		 * 这个默认类型过滤器我们在此前IoC容器初始化源码的<context:component-scan/>扩展标签解析的时候就讲了:
		 * 注册默认过滤器就是尝试添加@Component、@ManagedBean、@Named这三个注解类型过滤器到includeFilters缓存集合中！
		 *
		 * 这表示将会注册所有具有@Component注解及其派生注解的注解标志的类，比如@Component、@Repository、@Service、@Controller、@Configuration，
		 * 还支持扫描注册 Java EE 6 的注解，比如@ManagedBean，以及JSR-330的注解，比如@Named
		 */
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.registry,
				componentScan.getBoolean("useDefaultFilters"), this.environment, this.resourceLoader);
		/*
		 * 获取beanName生成器，默认是AnnotationBeanNameGenerator
		 */

		//获取nameGenerator属性值，即beanName生成器的class，默认值为BeanNameGenerator.class
		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		//判断是否是默认值，如果是默认值，那么使用当前创建ComponentScanAnnotationParser对象时指定的beanName生成器，
		//也就是ConfigurationClassPostProcessor类中的componentScanBeanNameGenerator，即AnnotationBeanNameGenerator
		//否则反射调用无参构造器，初始化指定类型的beanName生成器实例
		boolean useInheritedGenerator = (BeanNameGenerator.class == generatorClass);
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));
		//生成代理对象的模式，通常在web应用中使用
		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			//设置指定的模式
			scanner.setScopedProxyMode(scopedProxyMode);
		}
		else {
			//自动选择JDK代理或者CGLib代理
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}
		//控制符合组件检测条件的类文件，Spring推荐使用includeFilters和excludeFilters
		scanner.setResourcePattern(componentScan.getString("resourcePattern"));
		//遍历每一个Filter
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("includeFilters")) {
			//解析每一个Filter成为TypeFilter类型过滤器集合，继续遍历该集合
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				//设置到includeFilters属性中
				scanner.addIncludeFilter(typeFilter);
			}
		}
		//遍历每一个Filter
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("excludeFilters")) {
			//解析每一个Filter成为TypeFilter类型过滤器集合，继续遍历该集合
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				//设置到excludeFilters属性中
				scanner.addExcludeFilter(typeFilter);
			}
		}
		//获取lazyInit加载值，表示是否延迟初始化，默认false
		boolean lazyInit = componentScan.getBoolean("lazyInit");
		if (lazyInit) {
			//设置给beanDefinitionDefaults属性，这是设置一个默认值属性
			scanner.getBeanDefinitionDefaults().setLazyInit(true);
		}
		/*
		 * 扫描包
		 */

		/*
		 * 解析后的包路径字符串
		 * Spring将会对其解析、扫描其中的bean定义
		 */
		Set<String> basePackages = new LinkedHashSet<>();
		/*
		 * 获取basePackages属性数组，也就是传递的包路径字符串，默认是个空数组
		 */
		String[] basePackagesArray = componentScan.getStringArray("basePackages");
		for (String pkg : basePackagesArray) {
			/*
			 * 通过环境变量解析传递的路径字符串中的占位符，随后根据分隔符分割为一个路径字符串数组
			 * 支持以","、";"、" "、"\t"、"\n"中的任意字符作为分隔符来表示传递了多个包路径
			 */
			String[] tokenized = StringUtils.tokenizeToStringArray(this.environment.resolvePlaceholders(pkg),
					ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
			Collections.addAll(basePackages, tokenized);
		}
		/*
		 * 获取basePackageClasses属性数组，默认是个空数组
		 * basePackageClasses属性可以指定了一批类的class，Spring将解析class所在的包路径作为扫描路径
		 */
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			//解析class的packageName，存入basePackages包路径字符串中
			basePackages.add(ClassUtils.getPackageName(clazz));
		}
		/*
		 * 如果解析后的basePackages包数组为空，即没有手动传递包路经
		 */
		if (basePackages.isEmpty()) {
			//那么将当前@ComponentScan注解所在的类所属的包路径作为扫描的包路径
			//也就是，默认扫描当前@ComponentScan注解所在的包下面的所有bean定义
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}
		/*
		 * 最后添加一个ExcludeFilter到excludeFilters属性中，表示排除当前的配置类的扫描
		 */
		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});
		/*
		 * 上面都是一些准备的逻辑，doScan方法才是真正执行扫描的逻辑，通过ClassPathBeanDefinitionScanner执行
		 * 无论是注解配置还是XML的配置，扫描包都是调用的这个方法，我们在此前<context:component-scan/>的注解解析文章中已经讲过了
		 * 该方法将会扫描并且注册这些指定包路径下的bean定义
		 */
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

	private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
		List<TypeFilter> typeFilters = new ArrayList<>();
		FilterType filterType = filterAttributes.getEnum("type");

		for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
			switch (filterType) {
				case ANNOTATION:
					Assert.isAssignable(Annotation.class, filterClass,
							"@ComponentScan ANNOTATION type filter requires an annotation type");
					@SuppressWarnings("unchecked")
					Class<Annotation> annotationType = (Class<Annotation>) filterClass;
					typeFilters.add(new AnnotationTypeFilter(annotationType));
					break;
				case ASSIGNABLE_TYPE:
					typeFilters.add(new AssignableTypeFilter(filterClass));
					break;
				case CUSTOM:
					Assert.isAssignable(TypeFilter.class, filterClass,
							"@ComponentScan CUSTOM type filter requires a TypeFilter implementation");

					TypeFilter filter = ParserStrategyUtils.instantiateClass(filterClass, TypeFilter.class,
							this.environment, this.resourceLoader, this.registry);
					typeFilters.add(filter);
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
			}
		}

		for (String expression : filterAttributes.getStringArray("pattern")) {
			switch (filterType) {
				case ASPECTJ:
					typeFilters.add(new AspectJTypeFilter(expression, this.resourceLoader.getClassLoader()));
					break;
				case REGEX:
					typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(expression)));
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with String pattern: " + filterType);
			}
		}

		return typeFilters;
	}

}
