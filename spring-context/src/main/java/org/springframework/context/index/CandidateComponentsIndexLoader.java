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

package org.springframework.context.index;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.SpringProperties;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;

/**
 * Candidate components index loading mechanism for internal use within the framework.
 *
 * @author Stephane Nicoll
 * @since 5.0
 */
public final class CandidateComponentsIndexLoader {

	/**
	 * The location to look for components.
	 * <p>Can be present in multiple JAR files.
	 */
	public static final String COMPONENTS_RESOURCE_LOCATION = "META-INF/spring.components";

	/**
	 * System property that instructs Spring to ignore the index, i.e.
	 * to always return {@code null} from {@link #loadIndex(ClassLoader)}.
	 * <p>The default is "false", allowing for regular use of the index. Switching this
	 * flag to {@code true} fulfills a corner case scenario when an index is partially
	 * available for some libraries (or use cases) but couldn't be built for the whole
	 * application. In this case, the application context fallbacks to a regular
	 * classpath arrangement (i.e. as no index was present at all).
	 */
	public static final String IGNORE_INDEX = "spring.index.ignore";


	private static final boolean shouldIgnoreIndex = SpringProperties.getFlag(IGNORE_INDEX);

	private static final Log logger = LogFactory.getLog(CandidateComponentsIndexLoader.class);

	private static final ConcurrentMap<ClassLoader, CandidateComponentsIndex> cache =
			new ConcurrentReferenceHashMap<>();


	private CandidateComponentsIndexLoader() {
	}


	/**
	 * Load and instantiate the {@link CandidateComponentsIndex} from
	 * {@value #COMPONENTS_RESOURCE_LOCATION}, using the given class loader. If no
	 * index is available, return {@code null}.
	 * @param classLoader the ClassLoader to use for loading (can be {@code null} to use the default)
	 * @return the index to use or {@code null} if no index was found
	 * @throws IllegalArgumentException if any module index cannot
	 * be loaded or if an error occurs while creating {@link CandidateComponentsIndex}
	 */
	/**
	 * CandidateComponentsIndexLoader的方法
	 * <p>
	 * 从默认的文件路径"META-INF/spring.components"加载索引文件成为一个CandidateComponentsIndex实例并返回
	 * 如果没有找到任何文件或者文件中没有定义组件，则返回null
	 *
	 * @param classLoader 给定的类加载器
	 * @return 将要使用的CandidateComponentsIndex实例，如果没有找到索引文件则返回null
	 */
	@Nullable
	public static CandidateComponentsIndex loadIndex(@Nullable ClassLoader classLoader) {
		ClassLoader classLoaderToUse = classLoader;
		if (classLoaderToUse == null) {
			classLoaderToUse = CandidateComponentsIndexLoader.class.getClassLoader();
		}
		return cache.computeIfAbsent(classLoaderToUse, CandidateComponentsIndexLoader::doLoadIndex);
	}

	@Nullable
	private static CandidateComponentsIndex doLoadIndex(ClassLoader classLoader) {
		if (shouldIgnoreIndex) {
			return null;
		}

		try {
			//加载全部组件索引文件
			Enumeration<URL> urls = classLoader.getResources(COMPONENTS_RESOURCE_LOCATION);
			//如果没有加载到组件索引文件，直接返回null
			if (!urls.hasMoreElements()) {
				return null;
			}
			List<Properties> result = new ArrayList<>();
			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();
				Properties properties = PropertiesLoaderUtils.loadProperties(new UrlResource(url));
				result.add(properties);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Loaded " + result.size() + "] index(es)");
			}
			int totalCount = result.stream().mapToInt(Properties::size).sum();
			//如果没有从组件索引文件中加载到属性，同样返回null，否则返回CandidateComponentsIndex实例
			return (totalCount > 0 ? new CandidateComponentsIndex(result) : null);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to load indexes from location [" +
					COMPONENTS_RESOURCE_LOCATION + "]", ex);
		}
	}

}
