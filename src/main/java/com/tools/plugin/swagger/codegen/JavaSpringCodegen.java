package com.tools.plugin.swagger.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Sets;
import com.tools.plugin.swagger.SwaggerInlineModelResolver;
import io.swagger.codegen.CliOption;
import io.swagger.codegen.CodegenModel;
import io.swagger.codegen.CodegenOperation;
import io.swagger.codegen.SupportingFile;
import io.swagger.codegen.languages.SpringCodegen;
import io.swagger.models.*;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.collect.Sets.difference;
import static java.util.stream.Collectors.joining;

public class JavaSpringCodegen extends SpringCodegen {

    private static final String MODEL_NAME_PROP_MAP = "modelNamePropMap";
    private static final String MODEL_NAME_PROP_MAP_EXCEPTION_MESSAGE = "Prop map is not a valid file.";
    private static final String MODEL_NAME_PROP_MAP_NOT_EXISTS_MESSAGE = "Prop map file does not exists.";

    private Map<String, List<String>> ignoredIndexDefinitions = new HashMap<>();

    public JavaSpringCodegen() {
        this.projectFolder = "";
        this.sourceFolder = "";
        embeddedTemplateDir = templateDir = "toolkit-java-spring";
        cliOptions.add(new CliOption(MODEL_NAME_PROP_MAP, "Location of the external property map"));
    }

    public String getName() {
        return "toolkit-java-spring";
    }

    public void processOpts() {
        super.processOpts();
        this.supportingFiles.remove(new SupportingFile("README.mustache", "", "README.md"));
        this.supportingFiles.remove(new SupportingFile("pom.mustache", "", "pom.xml"));
    }

    @Override
    public void preprocessSwagger(Swagger swagger) {
        super.preprocessSwagger(swagger);
        for (Path path : swagger.getPaths().values()) {
            if (hasImplicitHead(path)) {
                path.setHead(null);
            }
        }

        if (additionalProperties.containsKey(MODEL_NAME_PROP_MAP)) {
            changeInlineModelTitles(swagger);
        }

        new SwaggerInlineModelResolver(swagger).flattenSwagger();
    }

    private void changeInlineModelTitles(Swagger swagger) {
        String propMapFileLocation = (String) additionalProperties.get(MODEL_NAME_PROP_MAP);
        if (propMapFileLocation == null) {
            return;
        }

        Map<String, Map<String, Object>> propTitleMap = populatePropTitleMap(propMapFileLocation);
        for (String title : propTitleMap.keySet()) {
            Map<String, Object> propMap = propTitleMap.get(title);
            if (isValidPropMap(propMap)) {
                Object prop = findPropertyWithKeywords(swagger, propMap);
                if (prop instanceof Model) {
                    ((Model) prop).setTitle(title);
                } else if (prop instanceof Property) {
                    ((Property) prop).setTitle(title);
                }
            }
        }
    }

    private Map<String, Map<String, Object>> populatePropTitleMap(String propMapFileLocation) {
        File inputFile = new File(propMapFileLocation);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException(MODEL_NAME_PROP_MAP_NOT_EXISTS_MESSAGE);
        }

        Map<String, Map<String, Object>> propTitleMap = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            propTitleMap = mapper.readValue(inputFile, Map.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException(MODEL_NAME_PROP_MAP_EXCEPTION_MESSAGE);
        }

        return propTitleMap;
    }

    private Object findPropertyWithKeywords(Swagger swagger, Map<String, Object> propMap) {
        String pathName = (String) propMap.get("path");
        String operationType = (String) propMap.get("httpMethod");
        HttpMethod httpMethod = HttpMethod.valueOf(operationType.toUpperCase());
        Operation operation = findOperation(swagger, pathName, httpMethod);
        if (operation == null) {
            return null;
        }

        List<String> propertyKeywords = (List) propMap.get("keywords");
        propertyKeywords = propertyKeywords == null ? Collections.EMPTY_LIST : propertyKeywords;

        String parameterName = (String) propMap.get("parameterName");
        if (parameterName != null) {
            List<BodyParameter> bodyParameters = operation.getParameters().stream()
                    .filter(parameter -> parameter instanceof BodyParameter
                            && parameter.getName().equals(parameterName))
                    .map(parameter -> (BodyParameter) parameter).filter(bp -> bp.getSchema() != null)
                    .collect(Collectors.toList());
            if (!bodyParameters.isEmpty()) {
                BodyParameter bodyParameter = bodyParameters.get(0);
                Model model = bodyParameter.getSchema();
                if (model instanceof ArrayModel) {
                    ArrayModel am = (ArrayModel) model;
                    if (am.getItems() instanceof ObjectProperty) {
                        return findProp(am.getItems(), propertyKeywords);
                    }
                } else if (model instanceof ModelImpl) {
                    return findProp(model, propertyKeywords);
                }
            }
        }

        String responseStatus = (String) propMap.get("status");
        if (responseStatus != null) {
            Response response = operation.getResponses().get(responseStatus);
            if (response != null) {
                return findProp(response.getSchema(), propertyKeywords);
            }
        }

        return null;
    }

    private boolean isValidPropMap(Map<String, Object> propMap) {
        String pathName = (String) propMap.get("path");
        if (pathName == null) {
            return false;
        }

        String operationType = (String) propMap.get("httpMethod");
        List<String> httpMethodList = Arrays.stream(HttpMethod.values()).map(HttpMethod::name)
                .collect(Collectors.toList());
        if (operationType == null || !httpMethodList.contains(operationType.toUpperCase())) {
            return false;
        }

        String parameterName = (String) propMap.get("parameterName");
        String responseStatus = (String) propMap.get("status");
        return parameterName != null || responseStatus != null;
    }

    private Operation findOperation(Swagger swagger, String pathName, HttpMethod httpMethod) {
        Path path = swagger.getPath(pathName);
        if (path == null) {
            return null;
        }

        return path.getOperationMap().get(httpMethod);
    }

    private Object findProp(Model model, List<String> propertyKeywords) {
        if (propertyKeywords.isEmpty()) {
            return model;
        }

        return findProp(model.getProperties(), propertyKeywords);
    }

    private Property findProp(Map<String, Property> props, List<String> propertyKeywords) {
        String propName = propertyKeywords.remove(0);
        Property property = props.get(propName);

        return findProp(property, propertyKeywords);
    }

    private Property findProp(Property property, List<String> propertyKeywords) {
        if (property instanceof ObjectProperty) {
            if (propertyKeywords.isEmpty()) {
                return property;
            }
            return findProp(((ObjectProperty) property).getProperties(), propertyKeywords);
        } else if (property instanceof ArrayProperty) {
            Property inner = ((ArrayProperty) property).getItems();
            return findInnerProp(inner, propertyKeywords);
        } else if (property instanceof MapProperty) {
            Property inner = ((MapProperty) property).getAdditionalProperties();
            return findInnerProp(inner, propertyKeywords);
        }

        return null;
    }

    private Property findInnerProp(Property innerProperty, List<String> propertyKeywords) {
        if (propertyKeywords.isEmpty()) {
            return innerProperty;
        }
        if (innerProperty instanceof ObjectProperty) {
            return findProp(((ObjectProperty) innerProperty).getProperties(), propertyKeywords);
        }

        return null;
    }

    private boolean hasImplicitHead(Path path) {
        return path.getHead() != null && path.getGet() != null;
    }

    @Override
    public Map<String, Object> postProcessOperations(Map<String, Object> objs) {
        Map<String, Object> operations = super.postProcessOperations(objs);
        HashMap innerOperations = (HashMap) operations.get("operations");
        List<CodegenOperation> actualOperations = (List<CodegenOperation>) innerOperations.get("operation");

        List<Endpoint> endpoints = new ArrayList<>();
        innerOperations.put("endpoint", endpoints);
        for (CodegenOperation operation : actualOperations) {
            Endpoint endpoint = new Endpoint();
            endpoint.operationIdUpperSnakeCase = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE,
                    operation.operationId);
            endpoint.path = operation.path;
            endpoints.add(endpoint);
        }

        return operations;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> postProcessAllModels(Map<String, Object> objects) {
        checkIndexedClasses(objects.keySet());

        for (Object values : objects.values()) {
            Map<String, Object> objectMap = (Map<String, Object>) values;

            List<Object> models = (List<Object>) objectMap.get("models");
            for (Object model : models) {
                Map<String, Object> modelMap = (Map<String, Object>) model;
                CodegenModel cm = (CodegenModel) modelMap.get("model");

                markVariableGeneratedStatus(objects, cm);
            }
        }

        return objects;
    }

    /* package */ void checkIndexedClasses(Set<String> classNames) {
        Sets.SetView<String> classesDiff = difference(ignoredIndexDefinitions.keySet(), classNames);
        if (!classesDiff.isEmpty()) {
            throw new IllegalArgumentException(
                    "Classes to ignore index for does not exists: " + classesDiff.stream().collect(joining(", ")));
        }
    }

    private void markVariableGeneratedStatus(Map<String, Object> objs, CodegenModel cm) {
        cm.allVars.forEach(v -> v.vendorExtensions.put("isGenerated", objs.keySet().contains(v.datatype)));
    }

    private static class Endpoint {
        public String operationIdUpperSnakeCase;
        public String path;

        @Override
        public boolean equals(java.lang.Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Endpoint agreement = (Endpoint) o;
            return Objects.equals(this.operationIdUpperSnakeCase, agreement.operationIdUpperSnakeCase)
                    && Objects.equals(this.path, agreement.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operationIdUpperSnakeCase, path);
        }
    }

    public void setIndexDefinitions(Map<String, List<String>> ignoredIndexDefinitions) {
        this.ignoredIndexDefinitions = ignoredIndexDefinitions;
    }
}
