/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyEditor;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 *
 * 参考资料: http://www.cnblogs.com/VergiLyn/p/6130188.html
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/** Parent bean factory, for bean inheritance support */ // 父级的 BeanFactory
	private BeanFactory parentBeanFactory;

	/** ClassLoader to resolve bean class names with, if necessary */ // ClassLoader 类加载器, 当需要解析 bean 类名时
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/** ClassLoader to temporarily resolve bean class names with, if necessary */ // 临时的 ClassLoader
	private ClassLoader tempClassLoader;

	/** Whether to cache bean metadata or rather reobtain it for every access */ // 每次访问获得的更精确的元数据, 是否再次缓存, 默认是 true
	private boolean cacheBeanMetadata = true;

	/** Resolution strategy for expressions in bean definition values */
	private BeanExpressionResolver beanExpressionResolver;

	/** Spring ConversionService to use instead of PropertyEditors */
	private ConversionService conversionService;

	/** Custom PropertyEditorRegistrars to apply to the beans of this factory */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars =
			new LinkedHashSet<PropertyEditorRegistrar>(4);

	/** Custom PropertyEditors to apply to the beans of this factory */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors =
			new HashMap<Class<?>, Class<? extends PropertyEditor>>(4);

	/** A custom TypeConverter to use, overriding the default PropertyEditor mechanism */
	private TypeConverter typeConverter;

	/** String resolvers to apply e.g. to annotation attribute values */
	private final List<StringValueResolver> embeddedValueResolvers = new LinkedList<StringValueResolver>();

	/** BeanPostProcessors to apply in createBean */
	private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<BeanPostProcessor>();

	/** Indicates whether any InstantiationAwareBeanPostProcessors have been registered */
	private boolean hasInstantiationAwareBeanPostProcessors;

	/** Indicates whether any DestructionAwareBeanPostProcessors have been registered */
	private boolean hasDestructionAwareBeanPostProcessors;

	/** Map from scope identifier String to corresponding Scope */
	private final Map<String, Scope> scopes = new LinkedHashMap<String, Scope>(8);

	/** Security context used when running with a SecurityManager */
	private SecurityContextProvider securityContextProvider;

	/** Map from bean name to merged RootBeanDefinition */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions =
			new ConcurrentHashMap<String, RootBeanDefinition>(256);

	/** Names of beans that have already been created at least once */ // 哪些已经至少被创建过一次的 beanName
	private final Set<String> alreadyCreated =
			Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>(256));

	/** Names of beans that are currently in creation */	// 正在创建的 bean 名称
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<Object>("Prototype beans currently in creation");


	/**
	 * Create a new AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * Create a new AbstractBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 * @see #getBean
	 */
	public AbstractBeanFactory(BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------
	// 获取 Ioc 容器中指定名称的 Bean
	@Override
	public Object getBean(String name) throws BeansException {
		// doGetBean 才是正真向 Ioc 容器获取被管理 Bean 的过程
		return doGetBean(name, null, null, false);
	}

	// 获取 Ioc 容器中指定名称和类型的 Bean
	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		return doGetBean(name, requiredType, null, false);
	}

	// 获取 Ioc 容器中指定名称和参数的 Bean
	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		return doGetBean(name, null, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	// 获取 Ioc 容器中指定名称, 类型 和 参数的 Bean
	public <T> T getBean(String name, Class<T> requiredType, Object... args) throws BeansException {
		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @param typeCheckOnly whether the instance is obtained for a type check,
	 * not for actual use
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	// 参考资料 http://www.cnblogs.com/ITtangtang/p/3978349.html
	// 正真实现向 Ioc 容器获取 Bean 的功能, 也是触发依赖注入功能的地方
	// 这里是实际取得 Bean 的地方, 也是触发依赖注入的地方
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			final String name, final Class<T> requiredType, final Object[] args, boolean typeCheckOnly)
			throws BeansException {

		// 根据指定的名称获取被管理 bean 的名称, 剥离指定名称中对容器的相关依赖
		// 如果指定的是别名, 将别名转换为规范的 Bean 名称
		// 返回真实的 bean 的名字
		final String beanName = transformedBeanName(name);
		Object bean;

		/**
		 * 要优先检测缓存的原因:
		 * 		因为在创建单例 bean 的时候, 可能存在依赖注入的情况, 而在创建依赖的时候为了避免循环依赖
		 * spring 的原则是不等 bean 创建完成就会将创建 bean 的 ObjectFactory提早暴露出来
		 * 也就是将 ObjectFactory 加入到缓存中, 一旦当前 bean 创建时存在需要依赖 另一个 bean 情况(bean可能在之前已创建过)
		 * 		则通过 DefaultSingletonBeanRegistry.getSingleton(String name) 可以得到已创建的 bean 的 sharedInstance
		 * 			(在方法内部, 也可能是找到了用于创建 sharedInstance的 ObjectFactory, 再在方法内部找到的 ObjectFactory创建新 sharedInstance)
		 */
		// 先从缓存中取是否已经有被创建过的单例类型的 bean, 对于单例模型的 Bean 整个 Ioc 容器中只创建一次, 不需要重复创建
		// Eagerly check singleton cache for manually registered singletons.
		// 检查缓存中是否有单例对象 bean 实例
		// 先从缓存中取得 Bean, 处理那些已经创建过的单例模式的 Bean, 对这种 Bean 请求不需要重复创建
		Object sharedInstance = getSingleton(beanName);
		// Ioc 容器创建单例模式 Bean 实例对象
		// 如果能从缓存中获得已缓存的单例 bean, 并且参数 args == null (只能在创建 prototype 时创建), 那么直接返回缓存中的 bean, 否则继续创建 bean
		if (sharedInstance != null && args == null) {
			if (logger.isDebugEnabled()) {
				// 如果指定名称的 Bean 在 容器中已有单例模式的 bean 被创建, 直接返回已经创建的 Bean
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.debug("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				}
				else {
					logger.debug("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			/**
			 * 获取给定 Bean 的实例对象, 主要是完成 FactoryBean 的相关处理
			 * 注意: BeanFactory 是管理容器中的 Bean 的工厂, 而 FactoryBean 是
			 * 创建对象的工厂 bean, 两者之间的区别
			 */
			// 返回对应的实例, 可能存在是 FactoryBean 的情况, 返回的实际shi通过 Factorybean 创建的 bean 实例(如果 beanName 的命名是 &开头, 那么返回的是 Factorybean 实例)
			// 这里的 getObjectForBeanInstance 完成是 FactoryBean 的相关处理, 以取得 FactoryBean 的生产结果
			bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
		}

		else {
			/**
			 * 不能在缓存中找到 单例 bean
			 * 只有单例模式下, 才会尝试解决循环依赖, 原型模式下, 直接抛出异常 BeanCurrentlyInCreationException
			 * 什么是循环依赖, 例如 A 中有B 属性, B 中也有 A 属性,但是 A 还未创建完成时, 会先去创建B, 但是创建B的时候又要先去创建 A, 造成循环依赖
			 */
			/**
			 * 缓存没有正在创建的单例Bean
			 * 缓存中已经有已经创建的原型模式的 Bean, 但是由于循环引用的问题导致实例化对象失败
			 */
			// Fail if we're already creating this bean instance:		// 失败 如果我们已经创建了 这个 Bean 的实例
			// We're assumably within a circular reference.				// 我们大概在循环引用
			if (isPrototypeCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(beanName);
			}
			/**
			 * 对 Ioc 容器中是否存在指定名称的 BeanDefinition进行检查, 首先检查是否
			 * 能在当前的 BeanFactory 中获取的所有需要的 bean, 如果不能则委托当前容器的
			 * 父级容器去查找, 如果还是找不到则沿着容器的继承体系向父级容器查找
			 */
			// Check if bean definition exists in this factory.			// 检测在 当前 factory 是否存在对 bean 的定义
			BeanFactory parentBeanFactory = getParentBeanFactory();

			/**
			 * 如果当前工厂 AbstractBeanFactory 存在父级 beanFactory
			 * 		且当前 AbstractBeanFactory 不存在 bean 定义(BeanDefinition), 则把创建 bean 的职责交给父级 Beanfactory
			 */
			// 当前容器的父级容器存在, 其当前容器中不存在指定名称的 Bean
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// 解析指定的 bean 名称的原始名称
				// Not found -> check parent.
				String nameToLookup = originalBeanName(name);
				// 递归父类查找
				if (args != null) {
					// Delegation to parent with explicit args.
					// 委派父级容器根据指定名称和显示的参数查找
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				else {
					// 委派父级容器根据指定名称和类型查找
					// No args -> delegate to standard getBean method.
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
			}

			// 创建的 bean 是否需要进行类型验证, 一般不需要
			// 是否是简单的只做类型检测, 不只是类型检测的话要加入缓存
			if (!typeCheckOnly) {
				// 把指定的 beanName 记录到 alreadyCreated Map 中(缓存), 表明该 beanName 已经创建过, 或即将创建 (beanFactory 用此来优化 beanName 重复创建)
				// 向容器标记指定的 Bean 已经创建
				markBeanAsCreated(beanName);
			}

			try {
				// 根据 Bean 的名字取得 BeanDefinition
				// 根据指定 Bean 名称获取其父级的 Bean 定义, 主要解决 Bean 继承时子类
				// 合并父类公共属性问题
				// 将存储XML配置文件的 GenericBeanDefinition 转换成 RootBeanDefinition, 如果指定的 beanName 是子 bean 的话, 还会合并父类的信息
				final RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				checkMergedBeanDefinition(mbd, beanName, args);

				// 获取当前的 Bean 所依赖 Bean, 这样会触发getBean 的递归调用, 直到取到一个都没有任何依赖的 Bean 为止
				// Guarantee initialization of beans that the current bean depends on.
				String[] dependsOn = mbd.getDependsOn();
				// 如果当前 Bean 有依赖 Bean
				if (dependsOn != null) {	// 若存在依赖, 则需要递归实例化依赖的 bean, 即 A 中有属性 B, B中有C, 则递归优先实例化 C, 再实例化 B, 再实例化 A
					for (String dep : dependsOn) {
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						// 把被依赖 Bean 注册给当前依赖的 Bean
						registerDependentBean(dep, beanName);	// 缓存依赖调用
						// 递归调用 getBean 方法, 获取当前 Bean 的依赖的 Bean
						getBean(dep); // 递归
					}
				}

				// 这里通过 createBean 方法创建 Singleton bean 的实例, 这里有一个会调函数 getObject, 会在 getSingleton 中调用 ObjectFactory 的 createBean
				// 创建单例模式的 Bean 实例对象
				// Create bean instance.
				// 依赖的 bean 实例化以后, 就可以创建bean自身的实例
				if (mbd.isSingleton()) { // 如果是 singleton
					// 这里使用一个匿名内部类, 创建 bean 实例对象, 并且注册给所依赖的对象
					sharedInstance = getSingleton(beanName, new ObjectFactory<Object>() {
						@Override
						public Object getObject() throws BeansException {
							try {
								// 创建一个指定 Bean 实例对象, 如果父级继承, 则合并子类和父类的定义
								// 调用 AbstractBeanFactory 的实现类 AbstractAutowireCapablebeanFactory 中的 createBean 方法
								return createBean(beanName, mbd, args);
							}
							catch (BeansException ex) {
								// Explicitly remove instance from singleton cache: It might have been put there
								// eagerly by the creation process, to allow for circular reference resolution.
								// Also remove any beans that received a temporary reference to the bean.
								// 显示的从容器单例模式 Bean 缓存中清除实例对象
								destroySingleton(beanName);
								throw ex;
							}
						}
					});
					// 获取给定 Bean 实例对象
					bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
				}
				// Ioc 容器创建原型模式 bean 实例对象
				else if (mbd.isPrototype()) {			// 如果是prototype
					// 原型模式(Prototype) 是每次都会创建一个新的对象
					// It's a prototype -> create a new instance.
					Object prototypeInstance = null;
					try {
						// 回调 beforePrototypeCreation 方法, 默认的功能是注册当前创建的原型对象
						beforePrototypeCreation(beanName);
						// 创建指定  bean 创建实例
						prototypeInstance = createBean(beanName, mbd, args);
					}
					finally {
						// 回调 afterPrototypeCreation 方法, 默认的功能告诉 Ioc 容器指定 Bean 的原型对象不再创建了
						afterPrototypeCreation(beanName);
					}
					// 获取给定 Bean 的实例对象
					bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
				}
				/**
				 *  要创建的 bean 即不是单例模式, 也不是原型模式, 则根据 bean 定义资源中
				 *  配置的生命周期范围, 选择实例化 Bean 的合适方法, 这种在 Web 应用程序中
				 *  比较常用: 如: request, session, application 等生命周期
				 */
				else {		// 指定的scope 上实例化 bean
					String scopeName = mbd.getScope();
					final Scope scope = this.scopes.get(scopeName);
					// bean 定义资源中没有配置生命周期的范围, 则 Bean 定义不合法
					if (scope == null) {
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try { // 这里又使用一个匿名内部类, 获取一个指定生命周期范围的实例
						Object scopedInstance = scope.get(beanName, new ObjectFactory<Object>() {
							@Override
							public Object getObject() throws BeansException {
								beforePrototypeCreation(beanName);
								try {
									return createBean(beanName, mbd, args);
								}
								finally {
									afterPrototypeCreation(beanName);
								}
							}
						});
						// 获取 给定 Bean 的实例对象
						bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
					}
					catch (IllegalStateException ex) {
						throw new BeanCreationException(beanName,
								"Scope '" + scopeName + "' is not active for the current thread; consider " +
								"defining a scoped proxy for this bean if you intend to refer to it from a singleton",
								ex);
					}
				}
			}
			catch (BeansException ex) {
				cleanupAfterBeanCreationFailure(beanName);
				throw ex;
			}
		}

		// 检查需要的类型是否匹配实际的 bean 实例化类型
		// 对创建的 Bean 实例对象进行类型检查, 如果没问题就返回这个新建创建的 Bean, 这个 Bean 已经包含依赖的关系
		// Check if required type matches the type of the actual bean instance.
		if (requiredType != null && bean != null && !requiredType.isAssignableFrom(bean.getClass())) {
			try {
				return getTypeConverter().convertIfNecessary(bean, requiredType);
			}
			catch (TypeMismatchException ex) {
				if (logger.isDebugEnabled()) {
					logger.debug("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		return (T) bean;
	}

	@Override
	public boolean containsBean(String name) {
		String beanName = transformedBeanName(name);
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean) {
				return (BeanFactoryUtils.isFactoryDereference(name) || ((FactoryBean<?>) beanInstance).isSingleton());
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		else if (containsSingleton(beanName)) {
			return true;
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		if (mbd.isSingleton()) {
			if (isFactoryBean(beanName, mbd)) {
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				return factoryBean.isSingleton();
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// In case of FactoryBean, return singleton status of created object if not a dereference.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// Singleton or scoped - not a prototype.
		// However, FactoryBean may still produce a prototype object...
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			final FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
					@Override
					public Boolean run() {
						return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
								!fb.isSingleton());
					}
				}, getAccessControlContext());
			}
			else {
				return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
						!fb.isSingleton());
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean) {
				if (!BeanFactoryUtils.isFactoryDereference(name)) {
					Class<?> type = getTypeForFactoryBean((FactoryBean<?>) beanInstance);
					return (type != null && typeToMatch.isAssignableFrom(type));
				}
				else {
					return typeToMatch.isInstance(beanInstance);
				}
			}
			else if (!BeanFactoryUtils.isFactoryDereference(name)) {
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					return true;
				}
				else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					Class<?> targetType = mbd.getTargetType();
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance) &&
							typeToMatch.isAssignableFrom(targetType)) {
						// Check raw class match as well, making sure it's exposed on the proxy.
						Class<?> classToMatch = typeToMatch.resolve();
						return (classToMatch == null || classToMatch.isInstance(beanInstance));
					}
				}
			}
			return false;
		}
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			return false;
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// Retrieve corresponding bean definition.
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		Class<?> classToMatch = typeToMatch.resolve();
		if (classToMatch == null) {
			classToMatch = FactoryBean.class;
		}
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});

		// Check decorated bean definition, if any: We assume it'll be easier
		// to determine the decorated bean's type than the proxy's type.
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
		if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
			RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
			Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
			if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
				return typeToMatch.isAssignableFrom(targetClass);
			}
		}

		Class<?> beanType = predictBeanType(beanName, mbd, typesToMatch);
		if (beanType == null) {
			return false;
		}

		// Check bean class whether we're dealing with a FactoryBean.
		if (FactoryBean.class.isAssignableFrom(beanType)) {
			if (!BeanFactoryUtils.isFactoryDereference(name)) {
				// If it's a FactoryBean, we want to look at what it creates, not the factory class.
				beanType = getTypeForFactoryBean(beanName, mbd);
				if (beanType == null) {
					return false;
				}
			}
		}
		else if (BeanFactoryUtils.isFactoryDereference(name)) {
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			if (beanType == null || !FactoryBean.class.isAssignableFrom(beanType)) {
				return false;
			}
		}

		ResolvableType resolvableType = mbd.targetType;
		if (resolvableType == null) {
			resolvableType = mbd.factoryMethodReturnType;
		}
		if (resolvableType != null && resolvableType.resolve() == beanType) {
			return typeToMatch.isAssignableFrom(resolvableType);
		}
		return typeToMatch.isAssignableFrom(beanType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				return getTypeForFactoryBean((FactoryBean<?>) beanInstance);
			}
			else {
				return beanInstance.getClass();
			}
		}
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			return null;
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.getType(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// Check decorated bean definition, if any: We assume it'll be easier
		// to determine the decorated bean's type than the proxy's type.
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
		if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
			RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
			Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
			if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
				return targetClass;
			}
		}

		Class<?> beanClass = predictBeanType(beanName, mbd);

		// Check bean class whether we're dealing with a FactoryBean.
		if (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass)) {
			if (!BeanFactoryUtils.isFactoryDereference(name)) {
				// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
				return getTypeForFactoryBean(beanName, mbd);
			}
			else {
				return beanClass;
			}
		}
		else {
			return (!BeanFactoryUtils.isFactoryDereference(name) ? beanClass : null);
		}
	}

	@Override
	public String[] getAliases(String name) {
		String beanName = transformedBeanName(name);
		List<String> aliases = new ArrayList<String>();
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		String fullBeanName = beanName;
		if (factoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		String[] retrievedAliases = super.getAliases(beanName);
		for (String retrievedAlias : retrievedAliases) {
			String alias = (factoryPrefix ? FACTORY_BEAN_PREFIX : "") + retrievedAlias;
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	public ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	public BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	public ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		this.propertyEditorRegistrars.add(registrar);
	}

	/**
	 * Return the set of PropertyEditorRegistrars.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * Return the custom TypeConverter to use, if any.
	 * @return the custom TypeConverter, or {@code null} if none specified
	 */
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public TypeConverter getTypeConverter() {
		TypeConverter customConverter = getCustomTypeConverter();
		if (customConverter != null) {
			return customConverter;
		}
		else {
			// Build default TypeConverter, registering custom editors.
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			typeConverter.setConversionService(getConversionService());
			registerCustomEditors(typeConverter);
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	public String resolveEmbeddedValue(String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
			if (result == null) {
				return null;
			}
		}
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		this.beanPostProcessors.remove(beanPostProcessor);
		this.beanPostProcessors.add(beanPostProcessor);
		if (beanPostProcessor instanceof InstantiationAwareBeanPostProcessor) {
			this.hasInstantiationAwareBeanPostProcessors = true;
		}
		if (beanPostProcessor instanceof DestructionAwareBeanPostProcessor) {
			this.hasDestructionAwareBeanPostProcessors = true;
		}
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * Return the list of BeanPostProcessors that will get applied
	 * to beans created with this factory.
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * Return whether this factory holds a InstantiationAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return this.hasInstantiationAwareBeanPostProcessors;
	}

	/**
	 * Return whether this factory holds a DestructionAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return this.hasDestructionAwareBeanPostProcessors;
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isInfoEnabled()) {
				logger.info("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		}
		else {
			if (logger.isDebugEnabled()) {
				logger.debug("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	@Override
	public Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		return this.scopes.get(scopeName);
	}

	/**
	 * Set the security context provider for this bean factory. If a security manager
	 * is set, interaction with the user code will be executed using the privileged
	 * of the provided security context.
	 */
	public void setSecurityContextProvider(SecurityContextProvider securityProvider) {
		this.securityContextProvider = securityProvider;
	}

	/**
	 * Delegate the creation of the access control context to the
	 * {@link #setSecurityContextProvider SecurityContextProvider}.
	 */
	@Override
	public AccessControlContext getAccessControlContext() {
		return (this.securityContextProvider != null ?
				this.securityContextProvider.getAccessControlContext() :
				AccessController.getContext());
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory) {
			AbstractBeanFactory otherAbstractFactory = (AbstractBeanFactory) otherFactory;
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.hasInstantiationAwareBeanPostProcessors = this.hasInstantiationAwareBeanPostProcessors ||
					otherAbstractFactory.hasInstantiationAwareBeanPostProcessors;
			this.hasDestructionAwareBeanPostProcessors = this.hasDestructionAwareBeanPostProcessors ||
					otherAbstractFactory.hasDestructionAwareBeanPostProcessors;
			this.scopes.putAll(otherAbstractFactory.scopes);
			this.securityContextProvider = otherAbstractFactory.securityContextProvider;
		}
		else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * @param name the name of the bean to retrieve the merged definition for
	 * (may be an alias)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		String beanName = transformedBeanName(name);

		// Efficiently check whether bean definition exists in this factory.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) getParentBeanFactory()).getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		else if (containsSingleton(beanName)) {
			// null instance registered
			return false;
		}

		// No singleton instance found -> check bean definition.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((ConfigurableBeanFactory) getParentBeanFactory()).isFactoryBean(name);
		}

		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * 返回 指定 bean 原型当前是否处于 正在创建
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 * @param beanName the name of the bean
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set && ((Set<?>) curVal).contains(beanName))));
	}

	/**
	 * Callback before prototype creation.
	 * <p>The default implementation register the prototype as currently in creation.
	 * @param beanName the name of the prototype about to be created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal == null) {
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		else if (curVal instanceof String) {
			Set<String> beanNameSet = new HashSet<String>(2);
			beanNameSet.add((String) curVal);
			beanNameSet.add(beanName);
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		else {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.add(beanName);
		}
	}

	/**
	 * Callback after prototype creation.
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * @param beanName the name of the prototype that has been created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		if (curVal instanceof String) {
			this.prototypesCurrentlyInCreation.remove();
		}
		else if (curVal instanceof Set) {
			Set<String> beanNameSet = (Set<String>) curVal;
			beanNameSet.remove(beanName);
			if (beanNameSet.isEmpty()) {
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * Destroy the given bean instance (usually a prototype instance
	 * obtained from this factory) according to the given bean definition.
	 * @param beanName the name of the bean definition
	 * @param bean the bean instance to destroy
	 * @param mbd the merged bean definition
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), getAccessControlContext()).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * 返回 bean 的名字
	 * Return the bean name, stripping out(剔除) the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names. 解决别名规范名称
	 * @param name the user-specified name
	 * @return the transformed bean name
	 */
	protected String transformedBeanName(String name) {
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * @param name the user-specified name
	 * @return the original bean name
	 */
	protected String originalBeanName(String name) {
		String beanName = transformedBeanName(name);
		if (name.startsWith(FACTORY_BEAN_PREFIX)) {
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		return beanName;
	}

	/**
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 * @param bw the BeanWrapper to initialize
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		bw.setConversionService(getConversionService());
		registerCustomEditors(bw);
	}

	/**
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * @param registry the PropertyEditorRegistry to initialize
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		PropertyEditorRegistrySupport registrySupport =
				(registry instanceof PropertyEditorRegistrySupport ? (PropertyEditorRegistrySupport) registry : null);
		if (registrySupport != null) {
			registrySupport.useConfigValueEditors();
		}
		if (!this.propertyEditorRegistrars.isEmpty()) {
			for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
				try {
					registrar.registerCustomEditors(registry);
				}
				catch (BeanCreationException ex) {
					Throwable rootCause = ex.getMostSpecificCause();
					if (rootCause instanceof BeanCurrentlyInCreationException) {
						BeanCreationException bce = (BeanCreationException) rootCause;
						if (isCurrentlyInCreation(bce.getBeanName())) {
							if (logger.isDebugEnabled()) {
								logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
										"] failed because it tried to obtain currently created bean '" +
										ex.getBeanName() + "': " + ex.getMessage());
							}
							onSuppressedException(ex);
							continue;
						}
					}
					throw ex;
				}
			}
		}
		if (!this.customEditors.isEmpty()) {
			for (Map.Entry<Class<?>, Class<? extends PropertyEditor>> entry : this.customEditors.entrySet()) {
				Class<?> requiredType = entry.getKey();
				Class<? extends PropertyEditor> editorClass = entry.getValue();
				registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass));
			}
		}
	}


	/**
	 * 返回一个合并 RootBeanDefinition, 将存储XML配置文件的 GenericBeanDefinition 转换成 RootBeanDefinition, 如果指定的 BeanName 是子 bean 的话, 还会合并其父类的信息
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * @param beanName the name of the bean to retrieve the merged definition for
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// 快速检查并发映射, 以最小的锁定()
		// Quick check on the concurrent map first, with minimal locking.
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		if (mbd != null) {
			return mbd;
		}
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @param containingBd the containing bean definition in case of inner bean,
	 * or {@code null} in case of a top-level bean
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, BeanDefinition containingBd)
			throws BeanDefinitionStoreException {

		synchronized (this.mergedBeanDefinitions) {
			RootBeanDefinition mbd = null;

			// Check with full lock now in order to enforce the same merged instance.
			if (containingBd == null) {
				// 全局锁检测是否存在了, 已合并的相同的 beanNames
				mbd = this.mergedBeanDefinitions.get(beanName);
			}

			if (mbd == null) {
				// 判断是否需要合并父类的 bean-definition
				if (bd.getParentName() == null) {
					// Use copy of given root bean definition.
					if (bd instanceof RootBeanDefinition) {
						mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
					}
					else {
						mbd = new RootBeanDefinition(bd);
					}
				}
				else { // 需要合并父类的 bean-definition
					// Child bean definition: needs to be merged with parent.
					BeanDefinition pbd;
					try {
						String parentBeanName = transformedBeanName(bd.getParentName());
						if (!beanName.equals(parentBeanName)) {
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						else {
							BeanFactory parent = getParentBeanFactory();
							if (parent instanceof ConfigurableBeanFactory) {
								pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
							}
							else {
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
										"': cannot be resolved without an AbstractBeanFactory parent");
							}
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					// Deep copy with overridden values.
					mbd = new RootBeanDefinition(pbd);
					mbd.overrideFrom(bd);
				}

				// 设置默认为 单例模式
				// Set default singleton scope, if not configured before.
				if (!StringUtils.hasLength(mbd.getScope())) {
					mbd.setScope(RootBeanDefinition.SCOPE_SINGLETON);
				}

				// 一个包含了非单例的 bean, 其本身不可能是单例, 则把指定的  beanNeam的 bean-definition 的 scope 指定为 containingBd.getScope()
				// A bean contained in a non-singleton bean cannot be a singleton itself.
				// Let's correct this on the fly here, since this might be the result of
				// parent-child merging for the outer bean, in which case the original inner bean
				// definition will not have inherited the merged outer bean's singleton status.
				// if 判断, 当指定 beanName 包含一个非单例的 bean, 且 beanName 自身是单例, 则把 beanName 的 scope 纠正为 其包含的 bean 的scope
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					mbd.setScope(containingBd.getScope());
				}

				/**
				 * 只是缓存一个合并后的 bean-definition, 如果我们已创建了 这个 beanName 的实例, 或者至少在之前已经创建过
				 * (把当前指定 beanName 的bean-definition 存到 mergedBeanDefinitions map 中做缓存)
				 * isCacheBeanMetadata(): 每次访问获得的更精确的元数据, 是否再次缓存, 默认 true
				 * isBeanEligibleForMetadataCaching(beanName): 确定指定的 beanName 是否有资格对其 bean-definition 元数据进行缓存
				 */
				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				if (containingBd == null && isCacheBeanMetadata()) {
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}

			return mbd;
		}
	}

	/**
	 * 校验给定的 merged-bean-definition
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 * @param mbd the merged bean definition to check
	 * @param beanName the name of the bean
	 * @param args the arguments for bean creation, if any
	 * @throws BeanDefinitionStoreException in case of validation failure
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, Object[] args)
			throws BeanDefinitionStoreException {

		if (mbd.isAbstract()) {
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 * @param beanName the bean name to clear the merged definition for
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		this.mergedBeanDefinitions.remove(beanName);
	}

	/**
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>Typically triggered after changes to the original bean definitions,
	 * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		Iterator<String> mergedBeans = this.mergedBeanDefinitions.keySet().iterator();
		while (mergedBeans.hasNext()) {
			if (!isBeanEligibleForMetadataCaching(mergedBeans.next())) {
				mergedBeans.remove();
			}
		}
	}

	/**
	 * 根据 RootBeanDefinition中设定的 beanClass 信息来解析 bean 得到Class<?>
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 * @param mbd the merged bean definition to determine the class for
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	protected Class<?> resolveBeanClass(final RootBeanDefinition mbd, String beanName, final Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {
		try {
			if (mbd.hasBeanClass()) {
				return mbd.getBeanClass();
			}
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(new PrivilegedExceptionAction<Class<?>>() {
					@Override
					public Class<?> run() throws Exception {
						return doResolveBeanClass(mbd, typesToMatch);
					}
				}, getAccessControlContext());
			}
			else {
				return doResolveBeanClass(mbd, typesToMatch);
			}
		}
		catch (PrivilegedActionException pae) {
			ClassNotFoundException ex = (ClassNotFoundException) pae.getException();
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
	}

	/**
	 * 如果 typeToMatch != null, 则会尝试 this.tempClassLoader 临时类加载, 去解析 bean 得到其类
	 * 否则, 用 this.beanClassloader, 去解析 bean 得到其类, 都可能返回 null
	 */
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {

		ClassLoader beanClassLoader = getBeanClassLoader();
		ClassLoader classLoaderToUse = beanClassLoader;
		if (!ObjectUtils.isEmpty(typesToMatch)) {	// 当 类型匹配 不是 null 时, 尝试用 tempClassLoader 临时类加载器去解析 bean 得到类
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (e.g. in a weaving scenario).
			ClassLoader tempClassLoader = getTempClassLoader();
			if (tempClassLoader != null) {
				classLoaderToUse = tempClassLoader;
				if (tempClassLoader instanceof DecoratingClassLoader) {
					DecoratingClassLoader dcl = (DecoratingClassLoader) tempClassLoader;
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}
		String className = mbd.getBeanClassName();
		if (className != null) {
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				if (evaluated instanceof Class) {
					return (Class<?>) evaluated;
				}
				else if (evaluated instanceof String) {
					return ClassUtils.forName((String) evaluated, classLoaderToUse);
				}
				else {
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			// When resolving against a temporary class loader, exit early in order
			// to avoid storing the resolved Class in the bean definition.
			if (classLoaderToUse != beanClassLoader) {
				return ClassUtils.forName(className, classLoaderToUse);
			}
		}
		return mbd.resolveBeanClass(beanClassLoader);				// 用 bean 的类加载器去解析 bean 得到类
	}

	/**
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 * @param value the value to check
	 * @param beanDefinition the bean definition that the value comes from
	 * @return the resolved value
	 * @see #setBeanExpressionResolver
	 */
	protected Object evaluateBeanDefinitionString(String value, BeanDefinition beanDefinition) {
		if (this.beanExpressionResolver == null) {
			return value;
		}
		Scope scope = (beanDefinition != null ? getRegisteredScope(beanDefinition.getScope()) : null);
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition to determine the type for
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type of the bean, or {@code null} if not predictable
	 */
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType != null) {
			return targetType;
		}
		if (mbd.getFactoryMethodName() != null) {
			return null;
		}
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * @param beanName the name of the bean
	 * @param mbd the corresponding bean definition
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
		return (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
	}

	/**
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean already.
	 * <p>The default implementation creates the FactoryBean via {@code getBean}
	 * to call its {@code getObjectType} method. Subclasses are encouraged to optimize
	 * this, typically by just instantiating the FactoryBean but not populating it yet,
	 * trying whether its {@code getObjectType} method already returns a type.
	 * If no type found, a full FactoryBean creation as performed by this implementation
	 * should be used as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @return the type for the bean if determinable, or {@code null} else
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 */
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		if (!mbd.isSingleton()) {
			return null;
		}
		try {
			FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
			return getTypeForFactoryBean(factoryBean);
		}
		catch (BeanCreationException ex) {
			if (ex instanceof BeanCurrentlyInCreationException) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean currently in creation on FactoryBean type check: " + ex);
				}
			}
			else if (mbd.isLazyInit()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on lazy FactoryBean type check: " + ex);
				}
			}
			else {
				if (logger.isWarnEnabled()) {
					logger.warn("Bean creation exception on non-lazy FactoryBean type check: " + ex);
				}
			}
			onSuppressedException(ex);
			return null;
		}
	}

	/**
	 * 标记指定的 beanName 为已创建(或即将创建), 这容许让 beanFactory 对重复创建的 beanName 做缓存优化
	 * Mark the specified bean as already created (or about to be created).
	 * <p>This allows the bean factory to optimize its caching for repeated
	 * creation of the specified bean.
	 * @param beanName the name of the bean
	 */
	protected void markBeanAsCreated(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			synchronized (this.mergedBeanDefinitions) {
				if (!this.alreadyCreated.contains(beanName)) {
					// Let the bean definition get re-merged now that we're actually creating
					// the bean... just in case some of its metadata changed in the meantime.
					clearMergedBeanDefinition(beanName);
					this.alreadyCreated.add(beanName);
				}
			}
		}
	}

	/**
	 * Perform appropriate cleanup of cached metadata after bean creation failed.
	 * @param beanName the name of the bean
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		synchronized (this.mergedBeanDefinitions) {
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * 确定指定的 beanName 是否有资格对 其 bean-definition 元数据进行缓存(如果 alreadyCreated map 中包含此 beanName, 返回 true)
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 * @param beanName the name of the bean
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 * @param beanName the name of the bean
	 * @return {@code true} if actually removed, {@code false} otherwise
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			removeSingleton(beanName);
			return true;
		}
		else {
			return false;
		}
	}

	/**
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 * @since 4.2.2
	 * @see #markBeanAsCreated
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * 获取给定 bean 的实例对象, 无论是 bean 实力本身 或 在 FactoryBean 下生成的 bean
	 * (主要是 beanName 是否以 & 开头, 是则返回 Factorybean; 否则返回 FactoryBean 创建 bean 实例)
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * @param beanInstance the shared bean instance
	 * @param name name that may include factory dereference prefix
	 * @param beanName the canonical bean name
	 * @param mbd the merged bean definition
	 * @return the object to expose for the bean
	 */
	// 获取给定 Bean 的实例对象, 主要是完成 FactoryBean 的相关处理
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, RootBeanDefinition mbd) {
		/**容器已经得到了 Bean 实例对象, 这个实例对象可能是一个普通的 Bean, 也可能是
		 * 一个工厂 Bean, 如果是一个工厂 Bean, 则使用它创建一个 Bean 实例对象, 如果
		 * 调用本身就像获得一个容器的引用, 则指定返回这个工厂 Bean 实例对象
		 * 如果指定的名称是容器的解析引用(dereference, 即使对象本身而非内存地址)
		 * 且Bean 实例也不是创建 Bean 实例对象的工厂 Bean
		 */
		// 如果这里不是对 FactoryBean 的调用, 那么结束处理
		// Don't let calling code try to dereference the factory if the bean isn't a factory.
		if (BeanFactoryUtils.isFactoryDereference(name) && !(beanInstance instanceof FactoryBean)) {
			throw new BeanIsNotAFactoryException(transformedBeanName(name), beanInstance.getClass());
		}

		/**
		 * 现在我们已经有了一个 bean 的实例化(及参数传入的 beanInstance), 它可能普通的 bean, 也可能是 FactoryBean
		 * 如果它是 FactoryBean, 我们就用这个 FactoryBean 创建一个新的 bean 实例化对象返回
		 * 		除非调用者想要就是一个 factory 引用 ( 即使 beanInstance 是 FactoryBean, 但也可以通过 beanName 的命名来返回 FactoryBean, 而非 FactoryBean创建的 Bean
		 * 			强制返回本身的实例 beanInstance, 这 beanName 以 & 开头,  BeanFactoryUtils.isFactoryDereference(name) 中判断的返回 true)
		 */
		// Now we have the bean instance, which may be a normal bean or a FactoryBean.
		// If it's a FactoryBean, we use it to create a bean instance, unless the
		// caller actually wants a reference to the factory.
		// 如果Bean 实例不是工厂 Bean, 或者指定名称是容器的解引用, 调用者向获取对容器的引用,
		// 则直接返回当前的 bean 实例
		if (!(beanInstance instanceof FactoryBean) || BeanFactoryUtils.isFactoryDereference(name)) {
			return beanInstance;		//	如果不是 FactoryBean 的情况, 返回其本身的 bean 实例化对象
		}
		// 处理指定名称不是容器的解引用, 或者根据名称获取的 bean 实例对象是一个工厂 Bean
		// 使用工厂 bean 创建一个 Bean 实例对象
		Object object = null;		// 当 beanInstance 是 FactoryBean, 且beanName不是以 & 开头, 即要返回的是通过 FactoryBean 创建的 bean 对象实例
		if (mbd == null) {
			// 从 bean 工厂缓存中获取给定名称的  bean 实例对象
			object = getCachedObjectForFactoryBean(beanName);	// 先从缓存中获取
		}
		// 让 bean 工厂生产给定名称的 bean 对象实例
		if (object == null) {
			// Return bean instance from factory. 到此 beanInstance 一定是 Factorybean, 进行 object -> FactoryBean 的类型转换
			FactoryBean<?> factory = (FactoryBean<?>) beanInstance;
			// 如果从 Bean 工厂生产的 bean 是单例模式的, 则缓存
			// Caches object obtained from FactoryBean if it is a singleton.
			// containsBeanDefinition(...) 检测 beanDefinitionMap 中(即所有已加载的类中) 是否已定义了 beanName
			if (mbd == null && containsBeanDefinition(beanName)) { // 已定义 beanName
				// 从容器中获取指定名称的 bean 定义, 如果继承基类, 则合并基类相关属性
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			// 是否是用户定义的而不是程序自身定义的
			// 如果从容器得到 Bean 定义信息, 并且 Bean 定义信息不是虚构的, 则让工厂
			// Bean 生产 bean 实例对象
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			// 调用 FactoryBeanRegistrySupport 类的 getObjectFromFactoryBean 方法, 实现工厂 bean 生产 Bean 对象实例的过程
			object = getObjectFromFactoryBean(factory, beanName, !synthetic);
		}
		return object;
	}

	/**
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 * @param beanName the name to check
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 * @param bean the bean instance to check
	 * @param mbd the corresponding bean definition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		return (bean != null &&
				(DisposableBeanAdapter.hasDestroyMethod(bean, mbd) || (hasDestructionAwareBeanPostProcessors() &&
						DisposableBeanAdapter.hasApplicableProcessors(bean, getBeanPostProcessors()))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 * @param mbd the bean definition for the bean
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		AccessControlContext acc = (System.getSecurityManager() != null ? getAccessControlContext() : null);
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			if (mbd.isSingleton()) {
				// Register a DisposableBean implementation that performs all destruction
				// work for the given bean: DestructionAwareBeanPostProcessors,
				// DisposableBean interface, custom destroy method.
				registerDisposableBean(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
			else {
				// A bean with a custom scope...
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				scope.registerDestructionCallback(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Check if this bean factory contains a bean definition with the given name.
	 * Does not consider any hierarchy this factory may participate in.
	 * Invoked by {@code containsBean} when no cached singleton instance is found.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method (模板设计模式) and the public interface method in that case.
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a bean definition with the given name
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to find a definition for
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
	 * if the bean definition cannot be resolved
	 * @throws BeansException in case of errors
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, Object[] args)
			throws BeanCreationException;

}
