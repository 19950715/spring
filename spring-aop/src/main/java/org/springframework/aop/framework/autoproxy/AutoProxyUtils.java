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

package org.springframework.aop.framework.autoproxy;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Conventions;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Utilities for auto-proxy aware components.
 * Mainly for internal use within the framework.
 *
 * @author Juergen Hoeller
 * @since 2.0.3
 * @see AbstractAutoProxyCreator
 */
public abstract class AutoProxyUtils {

	/**
	 * Bean definition attribute that may indicate whether a given bean is supposed
	 * to be proxied with its target class (in case of it getting proxied in the first
	 * place). The value is {@code Boolean.TRUE} or {@code Boolean.FALSE}.
	 * <p>Proxy factories can set this attribute if they built a target class proxy
	 * for a specific bean, and want to enforce that bean can always be cast
	 * to its target class (even if AOP advices get applied through auto-proxying).
	 * @see #shouldProxyTargetClass
	 * 指示给定 bean 是否应使用基于类的代理，即CGLIB代理
	 *  * 如果希望代理bean可以转化内目标bean类型，那么可以设置该属性并且值为true
	 *  * 确定值为："org.springframework.aop.framework.autoproxy.AutoProxyUtils.preserveTargetClass"
	 */
	public static final String PRESERVE_TARGET_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(AutoProxyUtils.class, "preserveTargetClass");

	/**
	 * Bean definition attribute that indicates the original target class of an
	 * auto-proxied bean, e.g. to be used for the introspection of annotations
	 * on the target class behind an interface-based proxy.
	 * @since 4.2.3
	 * @see #determineTargetClass
	 * /**
	 *  * Bean definition属性key，指示自动代理Bean的原始目标类，例如用于在基于接口的代理之后对目标类上的注解进行内省
	 *  * 确定值为："org.springframework.aop.framework.autoproxy.AutoProxyUtils.originalTargetClass"
	 *  */
	public static final String ORIGINAL_TARGET_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(AutoProxyUtils.class, "originalTargetClass");


	/**
	 * Determine whether the given bean should be proxied with its target
	 * class rather than its interfaces. Checks the
	 * {@link #PRESERVE_TARGET_CLASS_ATTRIBUTE "preserveTargetClass" attribute}
	 * of the corresponding bean definition.
	 * @param beanFactory the containing ConfigurableListableBeanFactory
	 * @param beanName the name of the bean
	 * @return whether the given bean should be proxied with its target class
	 */
	public static boolean shouldProxyTargetClass(
			ConfigurableListableBeanFactory beanFactory, @Nullable String beanName) {

		if (beanName != null && beanFactory.containsBeanDefinition(beanName)) {
			BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
			//如果具有PRESERVE_TARGET_CLASS_ATTRIBUTE属性并且值为true，那么表示应该使用基于类的代理
			return Boolean.TRUE.equals(bd.getAttribute(PRESERVE_TARGET_CLASS_ATTRIBUTE));
		}
		return false;
	}

	/**
	 * Determine the original target class for the specified bean, if possible,
	 * otherwise falling back to a regular {@code getType} lookup.
	 * @param beanFactory the containing ConfigurableListableBeanFactory
	 * @param beanName the name of the bean
	 * @return the original target class as stored in the bean definition, if any
	 * @since 4.2.3
	 * @see org.springframework.beans.factory.BeanFactory#getType(String)
	 */
	@Nullable
	public static Class<?> determineTargetClass(
			ConfigurableListableBeanFactory beanFactory, @Nullable String beanName) {

		if (beanName == null) {
			return null;
		}
		if (beanFactory.containsBeanDefinition(beanName)) {
			BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);
			Class<?> targetClass = (Class<?>) bd.getAttribute(ORIGINAL_TARGET_CLASS_ATTRIBUTE);
			if (targetClass != null) {
				return targetClass;
			}
		}
		return beanFactory.getType(beanName);
	}

	/**
	 * Expose the given target class for the specified bean, if possible.
	 * @param beanFactory the containing ConfigurableListableBeanFactory
	 * @param beanName the name of the bean
	 * @param targetClass the corresponding target class
	 * @since 4.2.3
	 */
	static void exposeTargetClass(
			ConfigurableListableBeanFactory beanFactory, @Nullable String beanName, Class<?> targetClass) {

		if (beanName != null && beanFactory.containsBeanDefinition(beanName)) {
			//设置一个ORIGINAL_TARGET_CLASS_ATTRIBUTE属性，值为targetClass
			beanFactory.getMergedBeanDefinition(beanName).setAttribute(ORIGINAL_TARGET_CLASS_ATTRIBUTE, targetClass);
		}
	}

	/**
	 * Determine whether the given bean name indicates an "original instance"
	 * according to {@link AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX},
	 * skipping any proxy attempts for it.
	 * @param beanName the name of the bean
	 * @param beanClass the corresponding bean class
	 * @since 5.1
	 * @see AutowireCapableBeanFactory#ORIGINAL_INSTANCE_SUFFIX
	 */
	static boolean isOriginalInstance(String beanName, Class<?> beanClass) {
		//如果没有设置beanName，或者beanName长度不等于（beanCassName的长度+".ORIGINAL"的长度）
		//那么返回false，表示不跳过
		if (!StringUtils.hasLength(beanName) || beanName.length() !=
				beanClass.getName().length() + AutowireCapableBeanFactory.ORIGINAL_INSTANCE_SUFFIX.length()) {
			return false;
		}
		//如果beanName以beanCassName开始，并且以.ORIGINAL结束，那么返回true，否则返回false
		return (beanName.startsWith(beanClass.getName()) &&
				beanName.endsWith(AutowireCapableBeanFactory.ORIGINAL_INSTANCE_SUFFIX));
	}

}
