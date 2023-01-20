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

import java.beans.Introspector;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link BeanNameGenerator} implementation for bean classes annotated with the
 * {@link org.springframework.stereotype.Component @Component} annotation or
 * with another annotation that is itself annotated with {@code @Component} as a
 * meta-annotation. For example, Spring's stereotype annotations (such as
 * {@link org.springframework.stereotype.Repository @Repository}) are
 * themselves annotated with {@code @Component}.
 *
 * <p>Also supports Java EE 6's {@link javax.annotation.ManagedBean} and
 * JSR-330's {@link javax.inject.Named} annotations, if available. Note that
 * Spring component annotations always override such standard annotations.
 *
 * <p>If the annotation's value doesn't indicate a bean name, an appropriate
 * name will be built based on the short name of the class (with the first
 * letter lower-cased). For example:
 *
 * <pre class="code">com.xyz.FooServiceImpl -&gt; fooServiceImpl</pre>
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 * @see org.springframework.stereotype.Component#value()
 * @see org.springframework.stereotype.Repository#value()
 * @see org.springframework.stereotype.Service#value()
 * @see org.springframework.stereotype.Controller#value()
 * @see javax.inject.Named#value()
 * @see FullyQualifiedAnnotationBeanNameGenerator
 */
public class AnnotationBeanNameGenerator implements BeanNameGenerator {

	/**
	 * A convenient constant for a default {@code AnnotationBeanNameGenerator} instance,
	 * as used for component scanning purposes.
	 * @since 5.2
	 */
	/**
	 * AnnotationBeanNameGenerator的属性
	 * <p>
	 * 默认AnnotationBeanNameGenerator的实例常量，用于组件扫描目的。单例模式的应用
	 */
	public static final AnnotationBeanNameGenerator INSTANCE = new AnnotationBeanNameGenerator();

	private static final String COMPONENT_ANNOTATION_CLASSNAME = "org.springframework.stereotype.Component";

	private final Map<String, Set<String>> metaAnnotationTypesCache = new ConcurrentHashMap<>();


	/**
	 1. AnnotationBeanNameGenerator的方法
	 2. <p>
	 3. 支持@Component以及它所有的派生注解，以及JavaEE的javax.annotation.@ManagedBean、以及JSR 330的javax.inject.@Named注解
	 4. 从注解中获取设置的beanName，如果没有设置，则使用Spring自己的规则生成beanName
	 5.  6. @param definition BeanDefinition
	 7. @param registry   BeanDefinitionRegistry
	 8. @return beanName
	 */
	@Override
	public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
		if (definition instanceof AnnotatedBeanDefinition) {
			//从类上的注解中查找指定的 beanName，就是看有没有设置注解的value属性值
			String beanName = determineBeanNameFromAnnotation((AnnotatedBeanDefinition) definition);
			if (StringUtils.hasText(beanName)) {
				// Explicit bean name found.
				return beanName;
			}
		}
		// Fallback: generate a unique default bean name.
		//否则自动生成唯一的默认 bean 名称。
		return buildDefaultBeanName(definition, registry);
	}

	/**
	 * Derive a bean name from one of the annotations on the class.
	 * @param annotatedDef the annotation-aware bean definition
	 * @return the bean name, or {@code null} if none is found
	 */
	/**
	 1. AnnotationBeanNameGenerator的方法
	 2. <p>
	 3. 从类上的符合条件的注解中查找指定的 beanName
	 4. 支持@Component以及它所有的派生注解，以及JavaEE的javax.annotation.@ManagedBean、以及JSR 330的javax.inject.@Named注解
	 5.  6. @param annotatedDef 注解感知的 bean 定义
	 7. @return bean name，如果未找到则返回null
	 8. @throws IllegalStateException 如果存在多个注解都指定了不同的beanName，那么直接抛出IllegalStateException
	 */
	@Nullable
	protected String determineBeanNameFromAnnotation(AnnotatedBeanDefinition annotatedDef) {
		//获取此 bean 定义的 bean 类的注解元数据
		AnnotationMetadata amd = annotatedDef.getMetadata();
		//获取该类上的全部注解的全路径名称集合
		Set<String> types = amd.getAnnotationTypes();
		//保存获取到的beanName，以及用于多个beanName的唯一性校验
		String beanName = null;
		/*遍历所有注解，获取beanName，并进行beanName唯一性校验*/
		for (String type : types) {
			//返回一个包含该注解的全部属性的映射实例，属性名 -> 属性值
			AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(amd, type);
			if (attributes != null) {
				//设置 注解全路径名称 -> 注解上的元注解全路径名集合 的map缓存
				Set<String> metaTypes = this.metaAnnotationTypesCache.computeIfAbsent(type, key -> {
					/*
					 * 获取该注解上的除了四个元注解之外的元注解集合
					 * 比如@Service获取到的就是[org.springframework.stereotype.Component,org.springframework.stereotype.Indexed]
					 * 比如@Component获取到的就是[org.springframework.stereotype.Indexed]
					 * 比如@Description获取到的就是[]
					 */
					Set<String> result = amd.getMetaAnnotationTypes(key);
					return (result.isEmpty() ? Collections.emptySet() : result);
				});
				/*
				 * 当前注解是否有资格作为获取组件名称的候选注解
				 */
				if (isStereotypeWithNameValue(type, metaTypes, attributes)) {
					//如果有资格，那么获取value属性的值
					Object value = attributes.get("value");
					if (value instanceof String) {
						String strVal = (String) value;
						if (StringUtils.hasLength(strVal)) {
							//如果beanName不为null，并且此前的beanName和刚获取的beanName不相等，那么抛出异常
							//即，如果设置了多个beanName，那么必须相等
							if (beanName != null && !strVal.equals(beanName)) {
								throw new IllegalStateException("Stereotype annotations suggest inconsistent " +
										"component names: '" + beanName + "' versus '" + strVal + "'");
							}
							beanName = strVal;
						}
					}
				}
			}
		}
		return beanName;
	}

	/**
	 * Check whether the given annotation is a stereotype that is allowed
	 * to suggest a component name through its annotation {@code value()}.
	 * @param annotationType the name of the annotation class to check
	 * @param metaAnnotationTypes the names of meta-annotations on the given annotation
	 * @param attributes the map of attributes for the given annotation
	 * @return whether the annotation qualifies as a stereotype with component name
	 */
	/**
	 1. AnnotationBeanNameGenerator的方法
	 2. <p>
	 3. 检查给定注解是否有资格作为获取组件名称的候选注解
	 4.  5. @param annotationType      要检查的注解全路径名
	 6. @param metaAnnotationTypes 给定注解上的元注解的全路径名集合
	 7. @param attributes          给定注解的属性映射
	 8. @return 该注解是否有资格作为具有组件名称的候选注解
	 筛选规则如下：

	 首先是一系列判断，使用isStereotype变量接收结果：
	 注解类型是否是"org.springframework.stereotype.Component"，即是否是@Component注解
	 或者注解上的元注解类型集合中是否包含"org.springframework.stereotype.Component"，即当前注解是否将@Component注解当成元注解
	 或者注解类型是否是"javax.annotation.ManagedBean"，即是否是JavaEE的@ManagedBean注解
	 或者注解类型是否是"javax.inject.Named"，即是否是JSR 330的@Named注解。
	 第一个判断的四个条件满足一个，isStereotype即为true，否则为false。
	 如果isStereotype为true，并且给定注解的属性映射attributes不为null，并且给定注解的属性映射中具有value属性。那么给定注解就有资格作为获取组件名称的候选注解。
	 */
	protected boolean isStereotypeWithNameValue(String annotationType,
			Set<String> metaAnnotationTypes, @Nullable Map<String, Object> attributes) {
		/*
		 * 判断isStereotype：
		 *  1 annotationType注解类型是否是"org.springframework.stereotype.Component"，即是否是@Component注解
		 *  2 或者metaAnnotationTypes注解上的元注解类型集合中是否包含"org.springframework.stereotype.Component"，即当前注解是否将@Component注解当成元注解
		 *  3 或者annotationType注解类型是否是"javax.annotation.ManagedBean"，即是否是JavaEE的@ManagedBean注解
		 *  4 或者annotationType注解类型是否是"javax.inject.Named"，即是否是JSR 330的@Named注解
		 *
		 * 以上条件满足一个，isStereotype即为true
		 */
		boolean isStereotype = annotationType.equals(COMPONENT_ANNOTATION_CLASSNAME) ||
				metaAnnotationTypes.contains(COMPONENT_ANNOTATION_CLASSNAME) ||
				annotationType.equals("javax.annotation.ManagedBean") ||
				annotationType.equals("javax.inject.Named");
		/*
		 * 继续判断：
		 *  1 如果isStereotype为true
		 *  2 并且给定注解的属性映射集合attributes不为null
		 *  3 并且给定注解的属性映射集合attributes中具有value属性
		 *
		 *  以上条件都满足，那么给定注解就有资格作为获取组件名称的候选注解
		 */
		return (isStereotype && attributes != null && attributes.containsKey("value"));
	}

	/**
	 * Derive a default bean name from the given bean definition.
	 * <p>The default implementation delegates to {@link #buildDefaultBeanName(BeanDefinition)}.
	 * @param definition the bean definition to build a bean name for
	 * @param registry the registry that the given bean definition is being registered with
	 * @return the default bean name (never {@code null})
	 */
	protected String buildDefaultBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
		return buildDefaultBeanName(definition);
	}

	/**
	 * Derive a default bean name from the given bean definition.
	 * <p>The default implementation simply builds a decapitalized version
	 * of the short class name: e.g. "mypackage.MyJdbcDao" -> "myJdbcDao".
	 * <p>Note that inner classes will thus have names of the form
	 * "outerClassName.InnerClassName", which because of the period in the
	 * name may be an issue if you are autowiring by name.
	 * @param definition the bean definition to build a bean name for
	 * @return the default bean name (never {@code null})
	 */
	/**
	 * 从给定的 bean 定义派生一个默认的 bean 名称。
	 * <p>
	 * 默认实现是:
	 * 1 首先获取简单类名，请注意，内部类的名称为"outerClassName.InnerClassName"
	 * 2 随后调用Java核心rt.jar包中的Introspector.decapitalize方法根据简单类名生成beanName
	 * 2.1 默认将返回小写的简单类名，但是如果类名有多个字符并且前两个字符都是大写时，将直接返回简单类名
	 *
	 * @param definition 用于生成 bean 名称的 bean 定义
	 * @return 默认的 bean 名称，从不为null
	 */
	protected String buildDefaultBeanName(BeanDefinition definition) {
		String beanClassName = definition.getBeanClassName();
		Assert.state(beanClassName != null, "No bean class name set");
		//获取简单类名
		String shortClassName = ClassUtils.getShortName(beanClassName);
		//调用Java核心rt.jar包中的Introspector的decapitalize方法生成beanName
		/**
		 * Java核心rt.jar包中的Introspector.decapitalize方法
		 * <p>
		 * 将第一个字符从大写转换为小写，但在特殊情况下，当有多个字符并且并且前两个字符都是大写时，将返回原值
		 * Thus "FooBah" becomes "fooBah" and "X" becomes "x", but "URL" stays as "URL".
		 *
		 * @param name 需要转换为小写的字符串
		 * @return 已经转换为小写的字符串
		 */
		return Introspector.decapitalize(shortClassName);
	}

}
