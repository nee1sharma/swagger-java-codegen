package com.tools.plugin.swagger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.parser.SwaggerParser;

public class SwaggerInlineModelResolverTest {

	@Test
	public void resolveInlineBodyParameterWithRequired() throws Exception {
		Swagger swagger = new Swagger();

		swagger.path("/hello",
				new Path()
						.get(new Operation()
								.parameter(
										new BodyParameter()
												.name("body").schema(
														new ModelImpl()
																.property("address",
																		new ObjectProperty()
																				.property("street",
																						new StringProperty()
																								.required(true))
																				.required(true))
																.property("name", new StringProperty())))));

		new SwaggerInlineModelResolver(swagger).flattenSwagger();

		Operation operation = swagger.getPaths().get("/hello").getGet();
		BodyParameter bp = (BodyParameter) operation.getParameters().get(0);
		Assert.assertTrue(bp.getSchema() instanceof RefModel);

		Model body = swagger.getDefinitions().get("body");
		Assert.assertTrue(body instanceof ModelImpl);

		ModelImpl impl = (ModelImpl) body;
		Assert.assertNotNull(impl.getProperties().get("address"));

		Property addressProperty = impl.getProperties().get("address");
		Assert.assertTrue(addressProperty instanceof RefProperty);
		Assert.assertTrue(addressProperty.getRequired());

		Model helloAddress = swagger.getDefinitions().get("hello_address");
		Assert.assertTrue(helloAddress instanceof ModelImpl);

		ModelImpl addressImpl = (ModelImpl) helloAddress;
		Assert.assertNotNull(addressImpl);

		Property streetProperty = addressImpl.getProperties().get("street");
		Assert.assertTrue(streetProperty instanceof StringProperty);
		Assert.assertTrue(streetProperty.getRequired());
	}

	@Test
	public void resolveInlineObjectWithEmptyProperties() throws Exception {
		Swagger swagger = new Swagger();

		ObjectProperty emptyProperty = new ObjectProperty();
		emptyProperty.setProperties(Collections.emptyMap());

		swagger.path("/hello", new Path().get(new Operation().parameter(
				new BodyParameter().name("body").schema(new ModelImpl().property("address", emptyProperty)))));

		new SwaggerInlineModelResolver(swagger).flattenSwagger();

		Operation operation = swagger.getPaths().get("/hello").getGet();
		BodyParameter bp = (BodyParameter) operation.getParameters().get(0);
		Assert.assertTrue(bp.getSchema() instanceof RefModel);

		Model body = swagger.getDefinitions().get("body");
		Assert.assertTrue(body instanceof ModelImpl);

		ModelImpl impl = (ModelImpl) body;
		Assert.assertNotNull(impl.getProperties().get("address"));

		Property addressProperty = impl.getProperties().get("address");
		Assert.assertTrue(addressProperty instanceof RefProperty);

		Model helloAddress = swagger.getDefinitions().get("hello_address");
		Assert.assertTrue(helloAddress instanceof ModelImpl);

		ModelImpl addressImpl = (ModelImpl) helloAddress;
		Assert.assertNotNull(addressImpl);
	}

	@Test
	public void flattenModelDefinitionPointers_givenReferenceThatReferencesModel_returnsSanitizedDefinition()
			throws URISyntaxException {
		// Given
		URI uri = this.getClass().getClassLoader().getResource("TestReferenceAReferenceSpec.yaml").toURI();

		SwaggerParser parser = new SwaggerParser();
		Swagger swagger = parser.read(uri.toString());

		// When
		new SwaggerInlineModelResolver(swagger).flattenSwagger();

		// Then
		assertThat(swagger.getDefinitions()).hasSize(1);
		Map.Entry<String, Model> modelEntry = swagger.getDefinitions().entrySet().iterator().next();
		assertThat(modelEntry.getKey()).isEqualTo("ReferenceRequest");
		assertThat(modelEntry.getValue().getProperties()).hasSize(1);
		Map.Entry<String, Property> property = modelEntry.getValue().getProperties().entrySet().iterator().next();
		assertThat(property.getValue().getDescription()).isEqualTo("16 digital account reference");
	}
}
