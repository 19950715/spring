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

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utilities for identifying {@link Configuration} classes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	public static final String CONFIGURATION_CLASS_FULL = "full";

	public static final String CONFIGURATION_CLASS_LITE = "lite";

	public static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final String ORDER_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "order");


	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);

	private static final Set<String> candidateIndicators = new HashSet<>(8);

	static {
		//加入@Component、@ComponentScan、@Import、@ImportResource注解类名
		candidateIndicators.add(Component.class.getName());
		candidateIndicators.add(ComponentScan.class.getName());
		candidateIndicators.add(Import.class.getName());
		candidateIndicators.add(ImportResource.class.getName());
	}


	/**
	 * Check whether the given bean definition is a candidate for a configuration class
	 * (or a nested component class declared within a configuration/component class,
	 * to be auto-registered as well), and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 */
	public static boolean checkConfigurationClassCandidate(
			BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {
		//获取类型名，如果为null或者工厂方法名不为null，说明不是普通bean定义或者是工厂方法bean，返回false
		String className = beanDef.getBeanClassName();
		//如果类的名称为空，或者当前的类己经指定了工厂方法属性，这就证明当前的
		//BeanDefinition肯定不是添加了注解@Configuration的配置类了
		if (className == null || beanDef.getFactoryMethodName() != null) {
			return false;
		}

		AnnotationMetadata metadata;
		//当前beanDef是注解类型的，且注解元数据中包含了bean类的全限定名称
		//如果是支持注解类型的bean定义，如果是采用组件注解添加的bean定义那么支持，并且元数据来自同一个类
		if (beanDef instanceof AnnotatedBeanDefinition &&
				className.equals(((AnnotatedBeanDefinition) beanDef).getMetadata().getClassName())) {
			// Can reuse the pre-parsed metadata from the given BeanDefinition...
			//获取BeanDefinition上的注解元数据信息
			//获取类元数据
			metadata = ((AnnotatedBeanDefinition) beanDef).getMetadata();
		}
		//BeanDefinition是AbstractBeanDefinition的实例，且存在类的名称的
		/*
		 * 如果是普通类型的bean定义,也就是通过XML配置添加的bean定义
		 */
		else if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			// Check already loaded Class if present...
			// since we possibly can't even load the class file for this Class.
			//获取所属类型
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			//如果当前要处理的BeanDefinition,是Spring内部组件类
			//BeanFactoryPostProcessor或者BeanPostProcessor
			//AopInfrastructureBean或者EventListenerFactory
			//此时当然不符合候选条件了
			//如果类型属于BeanFactoryPostProcessor，或者属于BeanPostProcessor，或者属于AopInfrastructureBean，或者属于EventListenerFactory
			//那么返回false，表示不是配置类
			if (BeanFactoryPostProcessor.class.isAssignableFrom(beanClass) ||
					BeanPostProcessor.class.isAssignableFrom(beanClass) ||
					AopInfrastructureBean.class.isAssignableFrom(beanClass) ||
					EventListenerFactory.class.isAssignableFrom(beanClass)) {
				return false;
			}
			//将BeanDefinition中得到的类名，封装为注解的元数据metadata
			//获取类元数据
			metadata = AnnotationMetadata.introspect(beanClass);
		}
		else {
			try {
				//或者直接通过MetadataReader,根据类的名称得到注解的元数据
				//直接获取元数据
				MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
				metadata = metadataReader.getAnnotationMetadata();
			}
			catch (IOException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Could not find class file for introspecting configuration annotations: " +
							className, ex);
				}
				return false;
			}
		}
		//从类的注解元数据中，获取注解@Configuration的元数据配置信息
		/*
		 * 解析类元数据，判断是否是配置类
		 */

		//获取该类上的@Configuration注解的属性映射map，包括以@Configuration注解为元注解的注解
		Map<String, Object> config = metadata.getAnnotationAttributes(Configuration.class.getName());
		/*
		 * 如果config不为null，表示存在@Configuration注解获取以@Configuration注解为元注解的注解
		 * 并且proxyBeanMethods属性的值为true，默认就是true
		 */
		if (config != null && !Boolean.FALSE.equals(config.get("proxyBeanMethods"))) {
			//那么设置当前bean定义的属性，bean定义的父类BeanMetadataAttributeAccessor的方法
			//org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass = full
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
		}
		/*
		 * 否则，如果是其他配置类
		 * */
		else if (config != null || isConfigurationCandidate(metadata)) {
			//那么设置当前bean定义的属性，bean定义的父类BeanMetadataAttributeAccessor的方法
			//org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass = lite
			beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
		}
		/*
		 * 否则，表示不是配置类，返回false
		 */
		else {
			return false;
		}

		// It's a full or lite configuration candidate... Let's determine the order value, if any.
		//获取注解中的@Order注解的值，并设置到BeanDefinition中
		//到这里，表示属于配置类
		//确定给定配置类元数据的顺序
		//获取当前bean定义的@Order注解的值
		Integer order = getOrder(metadata);
		//如果设置了order值
		if (order != null) {
			//那么设置当前bean定义的属性，bean定义的父类BeanMetadataAttributeAccessor的方法
			//org.springframework.context.annotation.ConfigurationClassPostProcessor.order = order值
			beanDef.setAttribute(ORDER_ATTRIBUTE, order);
		}
		//返回true
		return true;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered for
	 * configuration class processing; {@code false} otherwise
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		// 如果是接口，那么不考虑
		if (metadata.isInterface()) {
			return false;
		}

		// Any of the typical annotations found?
		/*
		 * 遍历candidateIndicators集合，如果当前类具有@Component、@ComponentScan、@Import、@ImportResource注解及其派生注解的任何一个
		 * 那么算作配置类
		 */
		for (String indicator : candidateIndicators) {
			if (metadata.isAnnotated(indicator)) {
				return true;
			}
		}

		// Finally, let's look for @Bean methods...
		//上面的循环没确定结果，那么继续查找@Bean注解标注的方法
		try {
			//如果该类的存在至少一个具有@Bean注解及其派生注解标注的方法，那么算作配置类
			return metadata.hasAnnotatedMethods(Bean.class.getName());
		}
		catch (Throwable ex) {
			if (logger.isDebugEnabled()) {
				logger.debug("Failed to introspect @Bean methods on class [" + metadata.getClassName() + "]: " + ex);
			}
			return false;
		}
	}

	/**
	 * Determine the order for the given configuration class metadata.
	 * @param metadata the metadata of the annotated class
	 * @return the {@code @Order} annotation value on the configuration class,
	 * or {@code Ordered.LOWEST_PRECEDENCE} if none declared
	 * @since 5.0
	 */
	@Nullable
	public static Integer getOrder(AnnotationMetadata metadata) {
		Map<String, Object> orderAttributes = metadata.getAnnotationAttributes(Order.class.getName());
		return (orderAttributes != null ? ((Integer) orderAttributes.get(AnnotationUtils.VALUE)) : null);
	}

	/**
	 * Determine the order for the given configuration class bean definition,
	 * as set by {@link #checkConfigurationClassCandidate}.
	 * @param beanDef the bean definition to check
	 * @return the {@link Order @Order} annotation value on the configuration class,
	 * or {@link Ordered#LOWEST_PRECEDENCE} if none declared
	 * @since 4.2
	 */
	public static int getOrder(BeanDefinition beanDef) {
		Integer order = (Integer) beanDef.getAttribute(ORDER_ATTRIBUTE);
		return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
	}

}
