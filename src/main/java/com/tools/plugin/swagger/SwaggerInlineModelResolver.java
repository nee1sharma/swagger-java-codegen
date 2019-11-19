package com.tools.plugin.swagger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.codegen.InlineModelResolver;
import io.swagger.models.ArrayModel;
import io.swagger.models.ComposedModel;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.Swagger;
import io.swagger.models.Xml;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;

public class SwaggerInlineModelResolver extends InlineModelResolver {
    private Swagger swagger;

    public SwaggerInlineModelResolver(Swagger swagger) {
        this.swagger = swagger;
    }

    public void flattenSwagger() {
        if (swagger.getDefinitions() == null) {
            swagger.setDefinitions(new HashMap<>());
        }

        // operations
        Map<String, Path> paths = swagger.getPaths();

        if (paths != null) {
            for (String pathname : paths.keySet()) {
                Path path = paths.get(pathname);

                for (Operation operation : path.getOperations()) {
                    List<Parameter> parameters = operation.getParameters();

                    if (parameters != null) {
                        for (Parameter parameter : parameters) {
                            if (parameter instanceof BodyParameter) {
                                BodyParameter bp = (BodyParameter) parameter;
                                if (bp.getSchema() != null) {
                                    Model model = bp.getSchema();
                                    if (model instanceof ModelImpl) {
                                        ModelImpl obj = (ModelImpl) model;
                                        if (obj.getType() == null || "object".equals(obj.getType())) {
                                            if (obj.getProperties() != null) {
                                                flattenSwaggerProperties(obj.getProperties(), pathname);
                                                String modelName = resolveModelfngkitName(obj.getTitle(), bp.getName());
                                                String existing = matchGenerated(model);
                                                if (existing != null) {
                                                    bp.setSchema(new RefModel(existing));
                                                } else {
                                                    bp.setSchema(new RefModel(modelName));
                                                    addGenerated(modelName, model);
                                                    swagger.addDefinition(modelName, model);
                                                }
                                            }
                                        }
                                    } else if (model instanceof ArrayModel) {
                                        ArrayModel am = (ArrayModel) model;
                                        Property inner = am.getItems();

                                        if (inner instanceof ObjectProperty) {
                                            ObjectProperty op = (ObjectProperty) inner;
                                            if (op.getProperties() != null) {
                                                flattenSwaggerProperties(op.getProperties(), pathname);
                                                am.setItems(createRefProperty(op, bp.getName()));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Map<String, Response> responses = operation.getResponses();
                    if (responses != null) {
                        for (String key : responses.keySet()) {
                            Response response = responses.get(key);
                            if (response.getSchema() != null) {
                                Property property = response.getSchema();
                                if (property instanceof ObjectProperty) {
                                    ObjectProperty op = (ObjectProperty) property;
                                    if (op.getProperties() != null) {
                                        response.setSchema(createRefPropertyWithVendor(op, "inline_response_" + key));
                                    }
                                } else if (property instanceof ArrayProperty) {
                                    ArrayProperty ap = (ArrayProperty) property;
                                    Property inner = ap.getItems();

                                    if (inner instanceof ObjectProperty) {
                                        ObjectProperty op = (ObjectProperty) inner;
                                        if (op.getProperties() != null) {
                                            flattenSwaggerProperties(op.getProperties(), pathname);
                                            ap.setItems(createRefPropertyWithVendor(op, "inline_response_" + key));
                                        }
                                    }
                                } else if (property instanceof MapProperty) {
                                    MapProperty mp = (MapProperty) property;

                                    Property innerProperty = mp.getAdditionalProperties();
                                    if (innerProperty instanceof ObjectProperty) {
                                        ObjectProperty op = (ObjectProperty) innerProperty;
                                        if (op.getProperties() != null) {
                                            flattenSwaggerProperties(op.getProperties(), pathname);
                                            mp.setAdditionalProperties(createRefProperty(op, "inline_response_" + key));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // flatten definitions that are pointers to other definitions
        flattenModelDefinitionPointers();

        // definitions
        Map<String, Model> models = swagger.getDefinitions();
        if (models != null) {
            List<String> modelNames = new ArrayList<String>(models.keySet());
            for (String modelName : modelNames) {
                Model model = models.get(modelName);
                if (model instanceof ModelImpl) {
                    ModelImpl m = (ModelImpl) model;

                    Map<String, Property> properties = m.getProperties();
                    flattenSwaggerProperties(properties, modelName);

                } else if (model instanceof ArrayModel) {
                    ArrayModel m = (ArrayModel) model;
                    Property inner = m.getItems();
                    if (inner instanceof ObjectProperty) {
                        ObjectProperty op = (ObjectProperty) inner;
                        if (op.getProperties() != null) {
                            m.setItems(createRefProperty(op, modelName + "_inner"));
                        }
                    }
                } else if (model instanceof ComposedModel) {
                    ComposedModel m = (ComposedModel) model;
                    if (m.getChild() != null) {
                        Map<String, Property> properties = m.getChild().getProperties();
                        flattenSwaggerProperties(properties, modelName);
                    }
                }
            }
        }
    }

    private void flattenModelDefinitionPointers() {
        if (swagger.getDefinitions() != null) {

            Map<String, Model> toAdd = new HashMap<>();
            List<String> toRemove = new ArrayList<>();

            for (Map.Entry<String, Model> modelEntry : swagger.getDefinitions().entrySet()) {
                if (modelEntry.getValue() instanceof RefModel) {
                    toAdd.putAll(getDefinitionToAdd(modelEntry.getKey(), (RefModel) modelEntry.getValue()));
                    toRemove.addAll(getDefinitionsToRemove(modelEntry.getKey(), (RefModel) modelEntry.getValue()));
                }
            }

            toRemove.forEach(k -> swagger.getDefinitions().remove(k));
            swagger.getDefinitions().putAll(toAdd);
        }
    }

    private Map<String, Model> getDefinitionToAdd(String originalRef, RefModel model) {
        String ref = model.getSimpleRef();
        Model pointedToDef = swagger.getDefinitions().get(ref);
        if (pointedToDef instanceof RefModel) {
            return getDefinitionToAdd(originalRef, (RefModel) pointedToDef);
        } else {
            return Collections.singletonMap(originalRef, pointedToDef);
        }
    }

    private List<String> getDefinitionsToRemove(String originalRef, RefModel model) {
        List<String> toRemove = new ArrayList<>();
        String ref = model.getSimpleRef();
        Model pointedToDef = swagger.getDefinitions().get(ref);
        toRemove.add(ref);
        if (pointedToDef instanceof RefModel) {
            toRemove.addAll(getDefinitionsToRemove(originalRef, (RefModel) pointedToDef));
        }
        return toRemove;
    }

    public void flattenSwaggerProperties(Map<String, Property> properties, String path) {
        if (properties == null) {
            return;
        }
        Map<String, Property> propsToUpdate = new HashMap<String, Property>();
        for (String key : properties.keySet()) {
            Property property = properties.get(key);
            if (property instanceof ObjectProperty && ((ObjectProperty) property).getProperties() != null) {
                ObjectProperty op = (ObjectProperty) property;
                propsToUpdate.put(key, createRefProperty(op, path + "_" + key));
            } else if (property instanceof ArrayProperty) {
                ArrayProperty ap = (ArrayProperty) property;
                Property inner = ap.getItems();

                if (inner instanceof ObjectProperty) {
                    ObjectProperty op = (ObjectProperty) inner;
                    if (op.getProperties() != null) {
                        flattenSwaggerProperties(op.getProperties(), path);
                        ap.setItems(createRefProperty(op, path + "_" + key));
                    }
                }
            } else if (property instanceof MapProperty) {
                MapProperty mp = (MapProperty) property;
                Property inner = mp.getAdditionalProperties();

                if (inner instanceof ObjectProperty) {
                    ObjectProperty op = (ObjectProperty) inner;
                    if (op.getProperties() != null) {
                        flattenSwaggerProperties(op.getProperties(), path);
                        mp.setAdditionalProperties(createRefProperty(op, path + "_" + key));
                    }
                }
            }
        }
        if (propsToUpdate.size() > 0) {
            for (String key : propsToUpdate.keySet()) {
                properties.put(key, propsToUpdate.get(key));
            }
        }
    }

    private String resolveModelfngkitName(String title, String key) {
        if (title == null) {
            return uniquefngkitName(key);
        } else {
            return uniquefngkitName(title);
        }
    }

    private String uniquefngkitName(String key) {
        int count = 0;
        boolean done = false;
        key = key.replaceAll("[^a-z_\\.A-Z0-9 ]", "");
        while (!done) {
            String name = key;
            if (count > 0) {
                name = key + "_" + count;
            }
            if (swagger.getDefinitions() == null) {
                return name;
            } else if (!swagger.getDefinitions().containsKey(name)) {
                return name;
            }
            count += 1;
        }
        return key;
    }

    private Model modelFromfngkitProperty(ObjectProperty object, String path) {
        String description = object.getDescription();
        String example = null;

        Object obj = object.getExample();
        if (obj != null) {
            example = obj.toString();
        }
        String name = object.getName();
        Xml xml = object.getXml();
        Map<String, Property> properties = object.getProperties();

        ModelImpl model = new ModelImpl();
        model.setDescription(description);
        model.setExample(example);
        model.setName(name);
        model.setXml(xml);

        if (properties != null) {
            flattenSwaggerProperties(properties, path);
            model.setProperties(properties);
        }

        return model;
    }

    private RefProperty createRefProperty(ObjectProperty op, String pathkey) {
        String modelName = resolveModelfngkitName(op.getTitle(), pathkey);
        Model model = modelFromfngkitProperty(op, modelName);
        String existing = matchGenerated(model);
        if (existing != null) {
            RefProperty refProperty = new RefProperty(existing);
            refProperty.setRequired(op.getRequired());
            return refProperty;
        } else {
            RefProperty refProperty = new RefProperty(modelName);
            refProperty.setRequired(op.getRequired());
            addGenerated(modelName, model);
            swagger.addDefinition(modelName, model);
            return refProperty;
        }
    }

    private RefProperty createRefPropertyWithVendor(ObjectProperty op, String pathkey) {
        String modelName = resolveModelfngkitName(op.getTitle(), pathkey);
        Model model = modelFromfngkitProperty(op, modelName);
        String existing = matchGenerated(model);
        if (existing != null) {
            RefProperty refProperty = (RefProperty) this.makeRefProperty(existing, op);
            refProperty.setRequired(op.getRequired());
            return refProperty;
        } else {
            RefProperty refProperty = (RefProperty) this.makeRefProperty(modelName, op);
            refProperty.setRequired(op.getRequired());
            addGenerated(modelName, model);
            swagger.addDefinition(modelName, model);
            return refProperty;
        }
    }
}
