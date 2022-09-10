package dev.hilla.parser.plugins.backbone;

import javax.annotation.Nonnull;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.hilla.parser.core.AbstractPlugin;
import dev.hilla.parser.core.PluginConfiguration;
import dev.hilla.parser.models.ClassInfoModel;
import dev.hilla.parser.models.ClassRefSignatureModel;
import dev.hilla.parser.models.FieldInfoModel;
import dev.hilla.parser.node.EntityNode;
import dev.hilla.parser.node.NodeDependencies;
import dev.hilla.parser.node.NodePath;
import dev.hilla.parser.node.RootNode;
import dev.hilla.parser.node.TypeSignatureNode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

import static io.swagger.v3.oas.models.Components.COMPONENTS_SCHEMAS_REF;

public class EntityPlugin extends AbstractPlugin<PluginConfiguration> {
    @Nonnull
    @Override
    public NodeDependencies scan(@Nonnull NodeDependencies nodeDependencies) {
        if (!(nodeDependencies.getNode() instanceof TypeSignatureNode)) {
            return nodeDependencies;
        }

        var node = nodeDependencies.getNode();
        if (!(node.getSource() instanceof ClassRefSignatureModel)) {
            return nodeDependencies;
        }

        var ref = (ClassRefSignatureModel) node.getSource();
        if (ref.isJDKClass() || ref.isDate()) {
            return nodeDependencies;
        }

        return NodeDependencies.of(nodeDependencies.getNode(),
            nodeDependencies.getChildNodes(),
            Stream.concat(Stream.of(EntityNode.of(ref.getClassInfo())),
                nodeDependencies.getRelatedNodes()));
    }

    @Override
    public void enter(NodePath<?> nodePath) {
        if (nodePath.getNode() instanceof EntityNode) {
            var entityNode = (EntityNode) nodePath.getNode();
            var cls = entityNode.getSource();
            entityNode.setTarget(
                cls.isEnum() ? enumSchema(cls) : new ObjectSchema());
        }
    }

    @Override
    public void exit(NodePath<?> nodePath) {
        if (nodePath.getNode() instanceof EntityNode &&
            nodePath.getParentPath().getNode() instanceof RootNode) {
            var schema = (Schema<?>) nodePath.getNode().getTarget();
            var cls = (ClassInfoModel) nodePath.getNode().getSource();
            var openApi = (OpenAPI) nodePath.getParentPath().getNode()
                .getTarget();

            attachSchemaWithNameToOpenApi(schema, cls.getName(), openApi);
        }
    }

    private void attachSchemaWithNameToOpenApi(Schema<?> schema, String name,
        OpenAPI openApi) {
        var components = openApi.getComponents();

        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        components.addSchemas(name, schema);
    }

    private Schema<?> enumSchema(ClassInfoModel entity) {
        var schema = new StringSchema();

        schema.setEnum(entity.getFieldsStream().filter(FieldInfoModel::isPublic)
            .map(FieldInfoModel::getName).collect(Collectors.toList()));

        return schema;
    }
}