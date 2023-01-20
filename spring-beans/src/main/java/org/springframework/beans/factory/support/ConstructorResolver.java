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

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be later replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 *  因此，我们可以说ConstructorResolver的autowireConstructor方法才是核心方法，大概步骤为：
	 *
	 * 新建一个BeanWrapperImpl实例用来包装bean实例。
	 * 尝试查找缓存，看是否存在已被解析的构造器constructorToUse和参数argsToUse，如果不都存在，那么需要解析最适合的构造器和对应的构造器参数：
	 * 如果仅有一个候选构造器，并且外部没有传递参数，并且没有定义< constructor-arg >标签，并且这一个候选构造器就属于无参构造器，那么设置相关缓存，调用instantiate方法根据无参构造器进行反射实例化对象，并将实例化的对象存入当前bw的缓存中。随后返回bw，方法结束。
	 * 下面解析对有参构造器或者多个候选构造器进行解析。首先解析构造器参数，返回所需的最少参数个数minNrOfArgs，如果外部传递了构造器参数数组那么就是传递的数组长度，否则调用resolveConstructorArguments方法解析，默认实际上就是XML配置中的< constructor-arg/>子标签数量，因此对于基于注解配置的配置来说将返回0。
	 * 对候选构造器进行排序，主要按访问修饰符排序：public>protect>private，次要按构造器参数个数排序：参数多的排在前面。
	 * 从前向后遍历全部已排序的候选构造器数组，调用createArgumentArray方法解析该构造器所需的构造参数，以及判断当前构造器是否是合适的的构造器（比如参数个数小于最少参数个数的构造器不考虑、比如计算权重，越小权重的构造器优先级越高，这部分很复杂，看源码注释）。
	 * 遍历完毕最终会尝试找到一个最合适的构造器以及需要注入的对应的参数值，如果没找到最合适的构造器那么抛出异常“……Could not resolve matching constructor……”。
	 * 如果没有传递外部参数并且找到的用来实例化对象的构造器参数数组不为null，那么调用storeCache方法将已解析的构造器和参数存入缓存。
	 * 如果存在这两个缓存，或者解析完毕找到了最合适的构造器及其参数，那么调用instantiate方法根据找到的构造器和需要注入的依赖参数进行反射实例化对象，并将实例化的对象存入当前bw的缓存中。随后返回bw，方法结束。
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {
		//实例化BeanWrapperImpl实例用来包装bean实例
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//初始化BeanWrapper，设置转换服务ConversionService，注册自定义的属性编辑器PropertyEditor
		this.beanFactory.initBeanWrapper(bw);

		//最后被确定出来使用的构造
		//保存用来实例化的类的构造器
		Constructor<?> constructorToUse = null;
		//最后使用的参数
		//用来实例化的类的构造器参数Holder
		ArgumentsHolder argsHolderToUse = null;
		//最终确定的参数
		//用来实例化的类的构造器参数
		Object[] argsToUse = null;
		/*
		 * 1 尝试查找缓存，看是否存在已被解析的构造器和参数
		 */
		//如果外部参数不为null，一般都是null，即一般getBean方法不会指定参数
		if (explicitArgs != null) {
			//那么要使用的构造器参数，就等于外部传递的参数值
			argsToUse = explicitArgs;
		}
		//否则，尝试查找缓存
		else {
			//如果对这个构造方法解析过
			//argsToResolve用来表示需要解析的构造器参数
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				//尝试直接获取已解析的构造器或工厂方法（resolvedConstructorOrFactoryMethod----用于缓存已解析的构造器或工厂方法）
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				//如果不为null，并且constructorArgumentsResolved为true，constructorArgumentsResolved表示构造器参数是否已被解析
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					//如果构造器已被解析并且参数已被解析，那么直接获取已解析的构造器参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						//如果获取到的已解析的构造器参数为null，则获取准备用于解析的构造器参数
						//因为如果constructorArgumentsResolved为true，那么resolvedConstructorArguments
						// 和preparedConstructorArguments必有一个不为null
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			//如果argsToResolve不为null
			if (argsToResolve != null) {
				//实际中可能给字符串，最终可能使用可能是对象或者class
				//对缓存的构造器参数进行解析并进行类型转换，将结果赋值给argsToUse
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve, true);
			}
		}

		//没解析过
		/*
		 * 2 如果constructorToUse为null，或者argsToUse为null
		 * 表示缓存中没有已解析的构造器，需要解析构造器
		 */
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			//保存候选构造器
			Constructor<?>[] candidates = chosenCtors;
			//自动注入进入这个if
			//如果候选构造器数组为null，那么主动获取全部构造器作为候选构造器数组
			//对于XML的配置，candidates就是null，基于注解的配置candidates可能不会为null
			if (candidates == null) {
				//获取beanClass
				Class<?> beanClass = mbd.getBeanClass();
				try {
					//看是否允许访问非公开方法  如果允许拿到所有的构造方法，如果不允许则拿公开的构造方法
					//isNonPublicAccessAllowed判断是否允许访问非公共的构造器和方法
					//允许的话就反射调用getDeclaredConstructors获取全部构造器，否则反射调用getConstructors获取公共的构造器
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			//只有默认无参构造一个方法  mbd.hasConstructorArgumentValues() 从bd中获取构造方法参数
			/*
			 * 2.1 无参构造器解析，解析后会缓存解析结果，下次不会再次解析。
			 */
			//如果仅有一个候选构造器，并且外部没有传递参数，并且没有定义< constructor-arg >标签
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取该构造器
				Constructor<?> uniqueCandidate = candidates[0];
				//返回无参构造
				//如果该构造器没有参数，表示无参构造器，那简单，由于只有一个无参构造器
				//那么每一次创建对象都是调用这个构造器，所以将其加入缓存中
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						//argsHolderToUse.storeCache(mbd, constructorToUse); 跟这个一样  设置一些值标识已经解析过
						//设置已解析的构造器缓存属性为uniqueCandidate
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						//设置已解析的构造器参数标志位缓存属性为true
						mbd.constructorArgumentsResolved = true;
						//设置已解析的构造器参数缓存属性为EMPTY_ARGS，表示空参数
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					/*
					 * 到这一步，已经确定了构造器与参数，随后调用instantiate方法，
					 * 传递beanName、mbd、uniqueCandidate、EMPTY_ARGS初始化bean实例，随后设置到bw的相关属性中
					 */
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			/*
			 * 2.2 解析有参构造器
			 */
			/*
			 * 2.2.1 判断是否需要自动装配
			 * 如果候选构造器数组不为null，或者自动注入的模式为AUTOWIRE_CONSTRUCTOR，即构造器注入
			 * 那么需要自动装配，autowiring设置为true
			 */
			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			//构造器参数值的持有者，通常作为 bean 定义的一部分
			ConstructorArgumentValues resolvedValues = null;

			//表示我在实例化spring的时候找到的那个构造方法的参数最少要多少
			/*
			 * 2.2.2 解析构造器参数，返回参数个数
			 */
			//minNrOfArgs表示构造器参数个数
			int minNrOfArgs;
			//如果外部传递进来的构造器参数数组不为null，那么minNrOfArgs等于explicitArgs长度
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			//否则，解析构造器参数，返回参数个数
			else {
				//手动指定的构造方法  通过bd.getConstructorArgumentValues.addGenericArgumentValue("com.orderService")
				//获取bean定义中的constructorArgumentValues属性，前面解析标签的时候就说过了，这是对于全部<constructor-arg>子标签的解析时设置的属性
				//保存了基于XML的<constructor-arg>子标签传递的参数的名字、类型、索引、值等信息，对于基于注解的配置来说cargs中并没有保存信息，返回空对象
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				//创建一个新的ConstructorArgumentValues，用于保存解析后的构造器参数，解析就是将参数值转换为对应的类型
				resolvedValues = new ConstructorArgumentValues();
				//大多数情况为0  除非通过上面手动指定了构造方法的参数
				///把手动指定的cargs参数添加到 resolvedValues中分为有index和没有的
				//解析构造器参数，minNrOfArgs设置为解析构造器之后返回的参数个数，对于基于注解配置的配置来说将返回0
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}
			//首先public的在前面  然后最长参数的构造方法在前面
			/*
			 * 2.2.3 从候选构造器中选择合适的的构造器，以及构造参数
			 */
			//对候选构造器排序：主要按访问修饰符排序：public>protect>private，次要按构造器参数个数排序：参数多的排在前面
			AutowireUtils.sortConstructors(candidates);
			//最小的类型差异权重，值越小越匹配，初始化为int类型的最大值
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//具有歧义的构造器集合
			Set<Constructor<?>> ambiguousConstructors = null;
			//收集异常的集合
			LinkedList<UnsatisfiedDependencyException> causes = null;

			//自动装配的推断
			/*
			 * 遍历排序之后的候选构造器集合，找到最符合的构造器
			 */
			for (Constructor<?> candidate : candidates) {
				//获取当前构造器参数数量
				int parameterCount = candidate.getParameterCount();
				//如果缓存的已解析的构造器不为null，并且argsToUse不为null，并且参数长度大于parameterCount，即大于最大长度
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					//已经找到了最满足的贪婪构造器，因为当前构造器的参数个数已经是最多的了，不需要向后查找
					break;
				}
				//如果当前构造器参数个数小于最少参数个数，那么视为当前构造器不满足要求，跳过这个构造器，遍历下一个
				if (parameterCount < minNrOfArgs) {
					continue;
				}
				//参数数组持有者
				ArgumentsHolder argsHolder;
				//通过反射拿到这个方法参数的类型列表
				//获取当前构造器的参数类型数组
				Class<?>[] paramTypes = candidate.getParameterTypes();
				/*如果resolvedValues不为null，表示已解析构造器参数*/
				if (resolvedValues != null) {
					try {
						//获取当前构造器上的@ConstructorProperties注解标注的构造器参数名数组，这个注解的作用之一就是可以通过指定名称来改变xml文件中constructor-arg的name属性
						//这是JDK 1.6提供的注解，位于rt.jar包中。注意这不是指定别名，如果指定了@ConstructorProperties注解那么只能使用该注解指定的名字，这个注解基本没人用了
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						//如果获取的paramNames为null，表示该构造器上不存在@ConstructorProperties注解，基本上都会走这个逻辑
						if (paramNames == null) {
							//获取AbstractAutowireCapableBeanFactory中的parameterNameDiscoverer
							//参数名解析器，默认不为null，是DefaultParameterNameDiscoverer实例
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								//由于没有@ConstructorProperties注解，那么获取构造器上的参数名作为匹配的名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						//从spring容器中获取构造方法的参数值 可能有参数不存在spring容器中  第十五节课的重点
						//通过给定已解析的构造器参数值，创建参数数组以调用构造器或工厂方法，返回参数数组持有者
						//getUserDeclaredConstructor返回原始类的构造器，通常就是本构造器，除非是CGLIB生成的子类
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						//如果参数匹配失败
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// 保存匹配失败的异常信息，并且下一个构造器的尝试
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						//结束本次循环继续下一次循环
						continue;
					}
				}
				/*
				 * 如果resolvedValues为null，表示通过外部方法显式指定了参数
				 * 比如getBean方法就能指定参数数组
				 */
				else {
					// Explicit arguments given -> arguments length must match exactly.
					// Explicit arguments given -> arguments length must match exactly.
					//如果构造器参数数量不等于外部方法显式指定的参数数组长度，那么直接结束本次循环继续下一次循环，尝试匹配下一个构造器
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					//如果找到第一个参数长度相等的构造器，那么直接创建一个ArgumentsHolder
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				/*
				 * 类型差异权重计算
				 *
				 * isLenientConstructorResolution判断当前bean定义是以宽松模式还是严格模式解析构造器，工厂方法使用严格模式，其他默认宽松模式（true）
				 * 如果是宽松模式，则调用argsHolder的getTypeDifferenceWeight方法获取类型差异权重，宽松模式使用具有最接近的类型进行匹配
				 * getTypeDifferenceWeight方法用于获取最终参数类型arguments和原始参数类型rawArguments的差异，但是还是以原始类型优先，
				 * 因为原始参数类型的差异值会减去1024，最终返回它们的最小值
				 *
				 * 如果是严格模式，则调用argsHolder的getAssignabilityWeight方法获取类型差异权重，严格模式下必须所有参数类型的都需要匹配（同类或者子类）
				 * 如果有任意一个arguments（先判断）或者rawArguments的类型不匹配，那么直接返回Integer.MAX_VALUE或者Integer.MAX_VALUE - 512
				 * 如果都匹配，那么返回Integer.MAX_VALUE - 1024
				 *
				 *
				 */
				//计算差异值 选出值最小的那个
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 如果typeDiffWeight小于minTypeDiffWeight，表示此构造器是最接近的匹配项，那么选择此构造器
				if (typeDiffWeight < minTypeDiffWeight) {
					//设置属性值
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					//最终使用的参数值为argsHolder的arguments数组
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				// 否则，如果constructorToUse不为null，并且typeDiffWeight等于minTypeDiffWeight，即权重相等
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					//添加到具有歧义的ambiguousConstructors集合中
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}
			/*
			 * 2.2.4 遍历全部构造器完毕，可能找到了最合适的构造器，可能没找到，进行后续处理
			 */

			/*如果遍历全部候选构造器集合之后还是没找到将要使用的构造器，抛出异常*/
			if (constructorToUse == null) {
				//causes异常集合不为null，
				if (causes != null) {
					//获取并移除最后一个抛出的异常
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				//抛出异常：Could not resolve matching constructor……
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			/*
			 * 如果找到了将要使用的构造器，但是ambiguousConstructors也不为null，说明存在相同的权重的构造器
			 * 并且，如果isLenientConstructorResolution返回false，即严格模式
			 * 那么抛出异常：Ambiguous constructor matches found in bean……
			 */
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}
			//没有抛出异常，如果外部参数为null，并且找到的用来实例化对象的构造器参数不为null
			if (explicitArgs == null && argsHolderToUse != null) {
				//那么将解析的构造器和参数存入缓存
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		//实例化
		/*
		 * 3 最终调用instantiate方法，根据有参构造器和需要注入的依赖参数进行反射实例化对象，并将实例化的对象存入当前bw的缓存中
		 */
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			//获取bean实例化策略对象strategy，默认使用SimpleInstantiationStrategy实例
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			//安全管理器相关，不需要关系一般不会走这条路径
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				//委托strategy对象的instantiate方法创建bean实例
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}

		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve, true);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			if (candidates == null) {
				candidates = new ArrayList<>();
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				for (Method candidate : rawCandidates) {
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						candidates.add(candidate);
					}
				}
			}

			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Method uniqueCandidate = candidates.get(0);
				if (uniqueCandidate.getParameterCount() == 0) {
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					minNrOfArgs = 0;
				}
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;

			for (Method candidate : candidates) {
				int parameterCount = candidate.getParameterCount();

				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}

					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * * 将此 bean 的构造器参数解析为resolvedValues对象。这可能涉及查找、初始化其他bean类实例
	 *  * 此方法还用于处理静态工厂方法的调用。
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		//获取AbstractBeanFactory中的typeConverter自定义类型转换器属性，该属性用来覆盖默认属性编辑器PropertyEditor
		//并没有提供getCustomTypeConverter方法的默认返回，因此customConverter默认返回null，我们同样可以自己扩展
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如果自定义类型转换器customConverter不为null，那么就使用customConverter
		//否则使用bw对象本身，因为BeanWrapper也能实现类型转换，一般都是使用bw本身
		//这个converter用于解析TypedStringValues，将String类型转换为其他类型
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//初始化一个valueResolver对象，用于将 bean 定义对象中包含的值解析为应用于目标 bean 实例的实际值
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		//minNrOfArgs初始化为cargs内部的的indexedArgumentValues和genericArgumentValues两个集合的元素总数量
		//即XML的bean定义中的<constructor-arg>标签的数量
		int minNrOfArgs = cargs.getArgumentCount();
		/*
		 * 遍历、解析cargs的indexedArgumentValues集合，即带有index属性的<constructor-arg>标签解析之后存放的map集合
		 */
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			//获取key，key就是index
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			//如果index大于minNrOfArgs，则minNrOfArgs=index + 1，因为index从0开始的
			if (index + 1 > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			//获取已经解析的<constructor-arg>子标签
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			//判断是否已经转换过值类型，默认是没有转换的
			if (valueHolder.isConverted()) {
				//如果已经转换过，直接添加到resolvedValues对象的indexedArgumentValues集合中
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			//如果没有转换过，那么需要转换
			else {
				/*
				 * 之前解析<constructor-arg>子标签时，Spring会将对应的值的转换为了不同的包装类，比如设置值为<list>标签，那么转换为ManagedArray
				 * 如果设置值为<idref>标签，那么转换为RuntimeBeanNameReference，如果设置值为ref属性引用其他bean，那么转换为RuntimeBeanReference
				 * 这部分在parseConstructorArgElements和parsePropertyValue有讲解
				 *
				 * 这里的resolveValueIfNecessary方法，则是将之前的值包装类转换为对应的实例Java类型，比如ManagedArray转换为数组、ManagedList转换为list集合
				 * 如果是引用其他bean或者指定了另一个bean定义，比如RuntimeBeanReference，则在这里会先初始化该引用的bean
				 * 实例并返回，注意构造器注入没有循环依赖，但是方法注入和注解注入可能有循环依赖，这里就会处理，后面再说
				 * 相当于反解回来，这里的resolvedValue就是转换后的实际Java类型，这就是所谓的转换，这也是前面所说的运行时解析，就是这个逻辑
				 *
				 * 这里的argName传递的就是一个"constructor argument"字符串，实际上在解析构造器参数时这个参数没啥作用
				 * 但是resolveValueIfNecessary方法同样用于property的解析，那时就会传递propertyName
				 */
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				//重新创建一个新的resolvedValueHolder，设置value值为解析之后的值resolvedValue
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				//设置源
				resolvedValueHolder.setSource(valueHolder);
				//将解析之后的resolvedValueHolder添加到resolvedValues对象的indexedArgumentValues集合中
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}
		/*
		 * 遍历、解析cargs的genericArgumentValues集合，即不带index属性的<constructor-arg>标签解析之后存放的集合
		 */
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			//判断是否已经转换过值类型，默认是没有转换的
			if (valueHolder.isConverted()) {
				//如果已经转换过，直接添加到resolvedValues对象的genericArgumentValues集合中
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			//如果没有转换过，那么需要转换，和上面的逻辑是一样的
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				//将解析之后的resolvedValueHolder添加到resolvedValues对象的genericArgumentValues集合中
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}
		//返回minNrOfArgs
		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 *  * 使用给定已解析的构造器参数值，创建参数数组以调用构造器或工厂方法，返回参数数组持有者
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		//作用是有可能提供字符串，要通过这个字符串找到对象
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		//创建ArgumentsHolder对象用于存放参数数组
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		//已使用过的 ValueHolder 对象集，一个ValueHolder只能匹配一个参数
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		//自动注入的beanName的集合，用于注册依赖关系
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		/*
		 * 遍历参数类型数组
		 */
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			//获取参数类型以及对应的参数名
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			//从XML配置的参数中查找与当前遍历参数匹配的参数valueHolder
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				//从bd里面拿  一般为null
				//拿取手动设置的参数 当前匹配的构造方法参数不是指定的参数时 这里为null
				//当指定当参数为字符串时，比如com.luban.UserService 但是构造方法上的参数类型是Class类型，这也为null 然后就调用下面的方法拿到
				//根据index属性、name属性、type属性匹配一个没有被使用过的valueHolder
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				//paramTypes.length == resolvedValues.getArgumentCount()一般不成立 因为后面这个程序员手动指定的参数为0
				//如果没找到匹配的valueHolder，并且（不是自动注入，或者参数类型长度等于已解析的配置参数个数），那么需要进一步从已配置的参数中选取可以匹配的参数
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					//拿取手动设置的参数 自动装配的话 autowiring为true  这里就不会进来  因为自动装配后手动装配设置的参数就没用了
					//在genericArgumentValues中查找一个没有被使用过的valueHolder，暂不匹配type和name
					//以期望他可以在后面的匹配中，在类型转换后匹配（例如，String -> int）
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			/*如果valueHolder不为null，表示找到了一个潜在的匹配，下面尝试它到底行不行*/
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				//添加到已使用的usedValueHolders集合，
				usedValueHolders.add(valueHolder);
				//获取原始的originalValue
				Object originalValue = valueHolder.getValue();
				//已转换的convertedValue
				Object convertedValue;
				//如果当前valueHolder已转换过
				if (valueHolder.isConverted()) {
					//直接获取convertedValue集合
					convertedValue = valueHolder.getConvertedValue();
					//设置args的preparedArguments准备参数数组对应的索引的值为convertedValue
					args.preparedArguments[paramIndex] = convertedValue;
				}
				//否则，需要转换
				else {
					//根据构造器(方法)和参数索引获取一个MethodParameter工具类对象
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						//originalValue是指定的构造方法参数 做类型转换 因为有可能提供的是字符串 并且跟提供的参数和目前循环的构造方法参数做匹配，如果不匹配这里会抛异常
						//转换类型，尝试将originalValue转换为paramType类型，并且作为转换目标的methodParam方法参数（用于泛型匹配）
						//这里才是尝试将已解析的构造器值继续转换为参数类型的逻辑，在上面的resolveConstructorArguments中仅仅是将值得包装对象解析为Java值类型而已
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						//转换失败抛出异常
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					//获取最开始的源
					Object sourceHolder = valueHolder.getSource();
					//如果是ConstructorArgumentValues.ValueHolder类型
					//因为这里的resolvedValues都是由最开始的ValueHolder转换来的，因此该判断满足
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						//获取原值
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						//设置resolveNecessary属性为true，表示需要解析
						args.resolveNecessary = true;
						//设置args的preparedArguments准备参数数组对应的索引的值为sourceValue
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				//设置args的arguments参数数组对应的索引的值为convertedValue
				args.arguments[paramIndex] = convertedValue;
				//设置args的arguments原始参数数组对应的索引的值为originalValue
				args.rawArguments[paramIndex] = originalValue;
			}
			/*如果valueHolder为null，表示没找到匹配的参数*/
			else {
				//根据构造器(方法)和参数索引获取一个MethodParameter工具类对象
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				//如果既没有匹配到配置的参数也不是自动装配，那么说明装配失败，抛出异常：Ambiguous argument values for parameter ……
				//这个异常我相信很多人都见过吧！但是，这个异常并不一定会最终抛出，外面的autowireConstructor方法会捕获这个异常并做好记录
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				/*
				 * 到这一步还没抛出异常，说明是自动装配，这时需要到容器中去查找满足匹配条件的值
				 * 最常见的就是@Autowired修饰构造器，它的参数依赖注入就是在这个逻辑中完成的
				 */
				try {
					//这里就拿到了UserService
					//getBean拿到构造方法参数
					//构造器自动装配核心方法，到容器中去查找满足匹配条件的值，将会返回找到的满足条件的bean实例
					//这一步将可能触发其他bean定义的初始化
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					//解析出来的值赋值给args
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					//解析失败抛出异常，同样这个异常并不一定会最终抛出，外面的autowireConstructor方法会捕获这个异常并做好记录
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}
		/*遍历自动注入的beanName，这些bean也算作当前bean定义依赖的bean*/
		for (String autowiredBeanName : autowiredBeanNames) {
			//那么将autowiredBeanName和beanName的依赖关系注册到dependentBeanMap和dependenciesForBeanMap缓存中
			//这个方法我们在前面就讲过了
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve, boolean fallback) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			if (argValue == autowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, fallback);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 *  3. 用于解析自动装配的指定参数的模板方法。
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {
		//获取参数类型
		Class<?> paramType = param.getParameterType();
		//如果参数类型是InjectionPoint类型及其子类型，一般都不是
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			//获取当前的InjectionPoint，并返回
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			return injectionPoint;
		}
		try {
			//自动注入属性填充
			//内部委托beanFactory的resolveDependency方法来解析依赖
			//传递一个DependencyDescriptor依赖描述符，它表示一个注入点信息，可能是一个字段或者方法参数，这里它的required属性都为true，表示是必须的依赖
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			//如果只有一个候选构造器，那么对于集合类型应该返回一个空集
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				//如果依赖类型为数组，那么返回一个空数组
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				//如果依赖类型为Collection，那么返回一个对应依赖类型的空Collection
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				//如果依赖类型为Map，那么返回一个对应依赖类型的空Map
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			//如果有多个候选构造器，那么抛出异常
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}

}
