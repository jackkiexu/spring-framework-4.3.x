/*
 * Copyright 2002-2007 the original author or authors.
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

package org.springframework.beans.factory.parsing;

import org.springframework.core.io.Resource;

/**
 * Context that gets passed along a bean definition reading process,
 * encapsulating(封装) all relevant(相关) configuration as well as state.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 *
 * 参开资料
 * http://acooly.iteye.com/blog/1707354
 *
 */
public class ReaderContext {

	private final Resource resource;
	// 错误处理 FailFastProblemReporter
	private final ProblemReporter problemReporter;
	// 时间处理 EmptyReaderEventListener 空实现
	private final ReaderEventListener eventListener;
	// 源抽取器 NullSourceExtractor 空实现
	private final SourceExtractor sourceExtractor;


	public ReaderContext(Resource resource, ProblemReporter problemReporter,
			ReaderEventListener eventListener, SourceExtractor sourceExtractor) {

		this.resource = resource;
		this.problemReporter = problemReporter;
		this.eventListener = eventListener;
		this.sourceExtractor = sourceExtractor;
	}

	public final Resource getResource() {
		return this.resource;
	}


	public void fatal(String message, Object source) {
		fatal(message, source, null, null);
	}

	public void fatal(String message, Object source, Throwable ex) {
		fatal(message, source, null, ex);
	}

	public void fatal(String message, Object source, ParseState parseState) {
		fatal(message, source, parseState, null);
	}

	public void fatal(String message, Object source, ParseState parseState, Throwable cause) {
		Location location = new Location(getResource(), source);
		this.problemReporter.fatal(new Problem(message, location, parseState, cause));
	}

	public void error(String message, Object source) {
		error(message, source, null, null);
	}

	public void error(String message, Object source, Throwable ex) {
		error(message, source, null, ex);
	}

	public void error(String message, Object source, ParseState parseState) {
		error(message, source, parseState, null);
	}

	public void error(String message, Object source, ParseState parseState, Throwable cause) {
		Location location = new Location(getResource(), source);
		this.problemReporter.error(new Problem(message, location, parseState, cause));
	}

	public void warning(String message, Object source) {
		warning(message, source, null, null);
	}

	public void warning(String message, Object source, Throwable ex) {
		warning(message, source, null, ex);
	}

	public void warning(String message, Object source, ParseState parseState) {
		warning(message, source, parseState, null);
	}

	public void warning(String message, Object source, ParseState parseState, Throwable cause) {
		Location location = new Location(getResource(), source);
		this.problemReporter.warning(new Problem(message, location, parseState, cause));
	}


	public void fireDefaultsRegistered(DefaultsDefinition defaultsDefinition) {
		this.eventListener.defaultsRegistered(defaultsDefinition);
	}

	public void fireComponentRegistered(ComponentDefinition componentDefinition) {
		this.eventListener.componentRegistered(componentDefinition);
	}

	public void fireAliasRegistered(String beanName, String alias, Object source) {
		this.eventListener.aliasRegistered(new AliasDefinition(beanName, alias, source));
	}

	public void fireImportProcessed(String importedResource, Object source) {
		this.eventListener.importProcessed(new ImportDefinition(importedResource, source));
	}

	public void fireImportProcessed(String importedResource, Resource[] actualResources, Object source) {
		this.eventListener.importProcessed(new ImportDefinition(importedResource, actualResources, source));
	}


	public SourceExtractor getSourceExtractor() {
		return this.sourceExtractor;
	}

	public Object extractSource(Object sourceCandidate) {
		return this.sourceExtractor.extractSource(sourceCandidate, this.resource);
	}

}
