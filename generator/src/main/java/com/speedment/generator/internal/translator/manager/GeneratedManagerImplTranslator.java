/**
 *
 * Copyright (c) 2006-2016, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.generator.internal.translator.manager;

import com.speedment.common.codegen.constant.DefaultType;
import com.speedment.common.codegen.constant.SimpleParameterizedType;
import com.speedment.common.codegen.internal.util.Formatting;
import com.speedment.common.codegen.model.AnnotationUsage;
import com.speedment.common.codegen.model.Class;
import com.speedment.common.codegen.model.Constructor;
import com.speedment.common.codegen.model.Field;
import com.speedment.common.codegen.model.File;
import com.speedment.common.codegen.model.Import;
import com.speedment.common.codegen.model.Method;
import com.speedment.common.injector.Injector;
import com.speedment.common.injector.annotation.Inject;
import com.speedment.generator.component.TypeMapperComponent;
import com.speedment.generator.internal.util.EntityTranslatorSupport;
import com.speedment.generator.internal.util.FkHolder;
import com.speedment.generator.translator.AbstractEntityAndManagerTranslator;
import com.speedment.generator.translator.TranslatorSupport;
import com.speedment.runtime.component.DbmsHandlerComponent;
import com.speedment.runtime.component.ProjectComponent;
import com.speedment.runtime.component.resultset.ResultSetMapperComponent;
import com.speedment.runtime.component.resultset.ResultSetMapping;
import com.speedment.common.dbmodel.Column;
import com.speedment.common.dbmodel.Dbms;
import com.speedment.common.dbmodel.Table;
import com.speedment.runtime.exception.SpeedmentException;
import com.speedment.runtime.field.method.BackwardFinder;
import com.speedment.runtime.internal.util.sql.ResultSetUtil;
import com.speedment.runtime.manager.AbstractManager;
import com.speedment.runtime.manager.JdbcManagerSupport;
import com.speedment.runtime.manager.ManagerSupport;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.speedment.common.codegen.constant.DefaultAnnotationUsage.OVERRIDE;
import static com.speedment.common.codegen.internal.util.Formatting.block;
import static com.speedment.common.codegen.internal.util.Formatting.indent;
import static com.speedment.generator.internal.util.ColumnUtil.optionalGetterName;
import static com.speedment.generator.internal.util.ColumnUtil.usesOptional;
import static com.speedment.generator.internal.util.GenerateMethodBodyUtil.*;
import com.speedment.common.dbmodel.util.DocumentDbUtil;
import static com.speedment.runtime.util.DatabaseUtil.dbmsTypeOf;

/**
 *
 * @author Emil Forslund
 */
public final class GeneratedManagerImplTranslator extends AbstractEntityAndManagerTranslator<Class> {

    public final static String 
        NEW_ENTITY_FROM_METHOD         = "newEntityFrom",
        NEW_EMPTY_ENTITY_METHOD        = "newEmptyEntity",
        FIELDS_METHOD                  = "fields",
        PRIMARY_KEYS_FIELDS_METHOD     = "primaryKeyFields";

    private @Inject ResultSetMapperComponent resultSetMapperComponent;
    private @Inject DbmsHandlerComponent dbmsHandlerComponent;
    private @Inject TypeMapperComponent typeMappers;
    private @Inject Injector injector;
    
    public GeneratedManagerImplTranslator(Table table) {
        super(table, Class::of);
    }

    @Override
    protected Class makeCodeGenModel(File file) {
        
        final Map<Table, List<String>> fkStreamers = new HashMap<>();
        final Set<Type> fkManagers = new HashSet<>();
        final Set<Type> fkManagersReferencingThis = new HashSet<>();
        
        return newBuilder(file, getSupport().generatedManagerImplName())
            
            /**
             * Add streamers from back pointing foreign keys
             */
            .forEveryForeignKeyReferencingThis((clazz, fk) -> {
                final FkHolder fu = new FkHolder(injector, fk);
                
                final String methodName = EntityTranslatorSupport.FIND
                    + EntityTranslatorSupport.pluralis(fu.getTable(), getSupport().namer())
                    + "By" + getSupport().typeName(fu.getColumn());
                fkStreamers.computeIfAbsent(fu.getTable(), t -> new ArrayList<>()).add(methodName);
                
                final Type manager = fu.getEmt().getSupport().managerType();
                final String fkManagerName = getSupport().namer().javaVariableName(Formatting.shortName(manager.getTypeName()));
                if (fkManagersReferencingThis.add(manager)) {
                    clazz.add(Field.of(fkManagerName, manager)
                        .private_()
                        .add(AnnotationUsage.of(Inject.class))
                    );
                }
                
                final Type returnType = DefaultType.stream(fu.getEmt().getSupport().entityType());
                final Method method = Method.of(methodName, returnType)
                    .public_().add(OVERRIDE)
                    .add(Field.of("entity", fu.getForeignEmt().getSupport().entityType()))
                    .add("return " + methodName + "().apply(entity);");
                clazz.add(method);
                
                // Create corresponding streamer method
                clazz.add(Method.of(methodName, SimpleParameterizedType.create(BackwardFinder.class,
                        fu.getForeignEmt().getSupport().entityType(), 
                        fu.getEmt().getSupport().entityType()
                    ))
                    .public_().add(OVERRIDE)
                    .add("return " + 
                        fu.getEmt().getSupport().entityName() + "." +
                        getSupport().namer().javaStaticFieldName(fu.getColumn().getJavaName()) +
                        ".backwardFinder(" + fkManagerName + ");"
                    )
                );
            })
            
            /**
             * Add finder for ordinary foreign keys
             */
            .forEveryForeignKey((clazz, fk) -> {
                final FkHolder fu = new FkHolder(injector, fk);
                
                final Type manager = fu.getForeignEmt().getSupport().managerType();
                final String fkManagerName = getSupport().namer().javaVariableName(Formatting.shortName(manager.getTypeName()));
                if (fkManagers.add(manager)) {
                    clazz.add(Field.of(fkManagerName, manager)
                        .private_()
                        .add(AnnotationUsage.of(Inject.class))
                    );
                }

                final Type returnType;
                if (usesOptional(fu.getColumn())) {
                    file.add(Import.of(Optional.class));
                    returnType = DefaultType.optional(fu.getForeignEmt().getSupport().entityType());
                } else {
                    returnType = fu.getForeignEmt().getSupport().entityType();
                }

                final Method method = Method.of("find" + getSupport().typeName(fu.getColumn()), returnType)
                    .public_().add(OVERRIDE)
                    .add(Field.of("entity", fu.getEmt().getSupport().entityType()));
                
                if (usesOptional(fu.getColumn())) {
                    final String getterName = optionalGetterName(typeMappers, fu.getColumn()).get();
                    
                    method.add(
                        "if (entity.get" + getSupport().typeName(fu.getColumn()) + "().isPresent()) " + block(
                            "return " + fkManagerName + ".stream().filter(" +
                                getSupport().typeName(fu.getForeignTable()) + "." + 
                                getSupport().namer().javaStaticFieldName(fu.getForeignColumn().getJavaName()) + 
                                ".equal(entity.get" + getSupport().typeName(fu.getColumn()) + "()" + getterName + ")).findAny();"
                        ) + " else return Optional.empty();"
                    );

                } else {
                    file.add(Import.of(SpeedmentException.class));
                    method.add("return " + fkManagerName + ".stream().filter("
                        + getSupport().typeName(fu.getForeignTable()) + "." + getSupport().namer().javaStaticFieldName(fu.getForeignColumn().getJavaName()) + ".equal(entity.get" + getSupport().typeName(fu.getColumn()) + "())).findAny()\n"
                        + indent(".orElseThrow(() -> new SpeedmentException(\n"
                            + indent(
                                "\"Foreign key constraint error. "
                                + getSupport().typeName(fu.getForeignTable())
                                + " is set to \" + entity.get"
                                + getSupport().typeName(fu.getColumn()) + "()\n"
                            ) + "));"
                        )
                    );
                }
                clazz.add(method);
            })
            
            /**
             * The table specific methods.
             */
            .forEveryTable((clazz, table) -> {
                clazz
                    .public_()
                    .abstract_()
                    .setSupertype(SimpleParameterizedType.create(
                        AbstractManager.class,
                        getSupport().entityType()
                    ))
                    .add(getSupport().generatedManagerType())
                    .add(Field.of("projectComponent", ProjectComponent.class).add(AnnotationUsage.of(Inject.class)).private_())
                    
                    .add(Constructor.of().protected_())
                    
                    .add(Method.of("createSupport", SimpleParameterizedType.create(ManagerSupport.class, getSupport().entityType()))
                        .protected_().add(OVERRIDE)
                        .add(Field.of("injector", Injector.class))
                        .add("return " + JdbcManagerSupport.class.getSimpleName() + ".create(injector, this, this::" + NEW_ENTITY_FROM_METHOD + ");")
                        .call(() -> file.add(Import.of(JdbcManagerSupport.class)))
                    )
                    
                    .add(Method.of("getDbmsName", String.class)
                        .public_().add(OVERRIDE)
                        .add("return \"" + getSupport().dbmsOrThrow().getName() + "\";")
                    )
                    
                    .add(Method.of("getSchemaName", String.class)
                        .public_().add(OVERRIDE)
                        .add("return \"" + getSupport().schemaOrThrow().getName() + "\";")
                    )
                    
                    .add(Method.of("getTableName", String.class)
                        .public_().add(OVERRIDE)
                        .add("return \"" + getSupport().tableOrThrow().getName() + "\";")
                    )
                    
                    .add(generateNewEntityFrom(getSupport(), file, table::columns))
                    .add(generateNewEmptyEntity(getSupport(), file, table::columns))
                    .add(generateFields(getSupport(), file, FIELDS_METHOD, table::columns))
                    .add(generateFields(getSupport(), file, PRIMARY_KEYS_FIELDS_METHOD,
                        () -> table.columns().filter(GeneratedManagerImplTranslator::isPrimaryKey))
                    )
                    .add(generateNewCopyOf(file));
            })
            
            /**
             * Create aggregate streaming functions, if any
             */
            .forEveryTable(Phase.POST_MAKE, (clazz, table) -> {
                fkStreamers.keySet().forEach(referencingTable -> {
                    final List<String> methodNames = fkStreamers.get(referencingTable);
                    final TranslatorSupport<Table> foreignSupport = new TranslatorSupport<>(injector, referencingTable);

                    if (!methodNames.isEmpty()) {
                        final Method method = Method.of(
                            EntityTranslatorSupport.FIND +
                                EntityTranslatorSupport.pluralis(referencingTable, getSupport().namer()),
                            DefaultType.stream(foreignSupport.entityType())
                        ).public_().add(OVERRIDE);

                        method.add(Field.of("entity", getSupport().entityType()));

                        if (methodNames.size() == 1) {
                            method.add("return " + methodNames.get(0) + "(entity);");
                        } else {
                            file.add(Import.of(Function.class));
                            method.add("return Stream.of("
                                + methodNames.stream().map(n -> n + "(entity)").collect(Collectors.joining(", "))
                                + ").flatMap(Function.identity()).distinct();");
                        }
                        clazz.add(method);
                    }
                });
            })
            
            .build()
            .call(i -> file.add(Import.of(getSupport().entityImplType())));
    }

    @Override
    protected String getJavadocRepresentText() {
        return "The generated base implementation for the manager of every {@link " + 
            getSupport().entityType().getTypeName() + "} entity.";
    }

    @Override
    protected String getClassOrInterfaceName() {
        return getSupport().generatedManagerImplName();
    }

    @Override
    public boolean isInGeneratedPackage() {
        return true;
    }

    public Type getImplType() {
        return getSupport().managerImplType();
    }
    
    private Method generateNewEntityFrom(TranslatorSupport<Table> support, File file, Supplier<Stream<? extends Column>> columnsSupplier) {
        return Method.of(NEW_ENTITY_FROM_METHOD, support.entityType())
            .protected_()
            .add(SQLException.class)
            .add(SpeedmentException.class)
            .add(Field.of("resultSet", ResultSet.class))
            .add(generateNewEntityFromBody(this::readFromResultSet, support, file, columnsSupplier));
    }
    
    private String readFromResultSet(File file, Column c, AtomicInteger position) {
        
        final TranslatorSupport<Table> support = new TranslatorSupport<>(injector, c.getParentOrThrow());
        final Dbms dbms = c.getParentOrThrow().getParentOrThrow().getParentOrThrow();
        
        final ResultSetMapping<?> mapping = resultSetMapperComponent.apply(
            dbmsTypeOf(dbmsHandlerComponent, c.getParentOrThrow().getParentOrThrow().getParentOrThrow()),
            c.findDatabaseType()
        );

        final boolean isIdentityMapper = !c.getTypeMapper().isPresent();

        file.add(Import.of(DocumentDbUtil.class));
        
        final StringBuilder sb = new StringBuilder();
        if (!isIdentityMapper) {
            sb
                .append(typeMapperName(support, c))
                .append(".toJavaType(DocumentDbUtil.referencedColumn(projectComponent.getProject(), ")
                .append(support.entityName())
                .append(".")
                .append(support.namer().javaStaticFieldName(c.getJavaName()))
                .append(".identifier()), getEntityClass(), ");
        }
        final String getterName = "get" + mapping.getResultSetMethodName(dbms);

        final boolean isResultSetMethod = Stream.of(ResultSet.class.getMethods())
            .map(java.lang.reflect.Method::getName)
            .anyMatch(getterName::equals);

        final boolean isResultSetMethodReturnsPrimitive = Stream.of(ResultSet.class.getMethods())
            .filter(m -> m.getName().equals(getterName))
            .anyMatch(m -> m.getReturnType().isPrimitive());

        if (isResultSetMethod && !(usesOptional(c) && isResultSetMethodReturnsPrimitive)) {
            sb
                .append("resultSet.")
                .append("get")
                .append(mapping.getResultSetMethodName(dbms))
                .append("(").append(position.getAndIncrement()).append(")");
        } else {
            file.add(Import.of(ResultSetUtil.class).static_().setStaticMember("*"));
            sb
                .append("get")
                .append(mapping.getResultSetMethodName(dbms))
                .append("(resultSet, ")
                .append(position.getAndIncrement()).append(")");
        }
        if (!isIdentityMapper) {
            sb.append(")");
        }

        return sb.toString();
    }

    private Method generateNewCopyOf(File file) {
        file.add(Import.of(getSupport().entityImplType()));

        final String varName = "source";
        final String entityName = "copy";
        final Method result = Method.of("newCopyOf", getSupport().entityType()).public_().add(OVERRIDE)
            .add(Field.of(varName, getSupport().entityType()))
            .add("final " + getSupport().entityName() + " " + entityName + 
                " = new " + getSupport().entityImplName() + "();"
            );

        columns().forEachOrdered(c -> {
            if (usesOptional(c)) {
                result.add(
                    varName + "." + GETTER_METHOD_PREFIX + getSupport().typeName(c)
                    + "().ifPresent(" + entityName + "::"
                    + SETTER_METHOD_PREFIX + getSupport().typeName(c)
                    + ");"
                );
            } else {
                result.add(
                    entityName + "." + SETTER_METHOD_PREFIX
                    + getSupport().typeName(c) + "(" + varName + ".get"
                    + getSupport().typeName(c) + "());"
                );
            }
        });

        return result.add("", "return " + entityName + ";");
    }

    private static String typeMapperName(TranslatorSupport<Table> support, Column col) {
        return support.entityName() + "." + support.namer().javaStaticFieldName(col.getJavaName()) + ".typeMapper()";
    }

    private static boolean isPrimaryKey(Column column) {
        return column.getParentOrThrow().findPrimaryKeyColumn(column.getName()).isPresent();
    }
}