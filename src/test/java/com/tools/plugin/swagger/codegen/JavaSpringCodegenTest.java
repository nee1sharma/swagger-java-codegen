package com.tools.plugin.swagger.codegen;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class JavaSpringCodegenTest {

	private Map<String, List<String>> testIndexes = new HashMap<>();

	@Before
	public void setup() {
		testIndexes.put("Widget", new ArrayList<>());
		testIndexes.put("NotAClass", new ArrayList<>());
	}

	@Test
	public void test_check_index_classes_ok() {
		JavaSpringCodegen codegen = new JavaSpringCodegen();
		codegen.setIndexDefinitions(testIndexes);

		codegen.checkIndexedClasses(new HashSet<>(asList("Widget", "NotAClass")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void test_check_index_classes_fails() {
		JavaSpringCodegen codegen = new JavaSpringCodegen();
		codegen.setIndexDefinitions(testIndexes);

		codegen.checkIndexedClasses(singleton("Widget"));
	}
}