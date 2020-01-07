/*
 * Copyright 2015 Fizzed Inc.
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
package com.fizzed.rocker.reload;

import com.fizzed.rocker.RenderingException;
import com.fizzed.rocker.RockerModel;
import com.fizzed.rocker.TemplateBindException;
import com.fizzed.rocker.TemplateNotFoundException;
import com.fizzed.rocker.runtime.CompileDiagnosticException;
import com.fizzed.rocker.runtime.CompileUnrecoverableException;
import com.fizzed.rocker.compiler.GeneratorException;
import com.fizzed.rocker.runtime.ParserException;
import com.fizzed.rocker.compiler.RockerConfiguration;
import com.fizzed.rocker.compiler.TemplateCompiler;
import com.fizzed.rocker.runtime.DefaultRockerBootstrap;
import com.fizzed.rocker.runtime.DefaultRockerModel;
import com.fizzed.rocker.runtime.DefaultRockerTemplate;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author joelauer
 */

public class ReloadingRockerBootstrap extends DefaultRockerBootstrap {
    static private final Logger log = LoggerFactory.getLogger(ReloadingRockerBootstrap.class);
    
    static public class LoadedTemplate {
        public File file;                           // resolved path to template file
        public long modifiedAt;                     // modified date of version loaded
        public String headerHash;                   // hash of header (interface)
    }
    
    private RockerConfiguration configuration;
    private final ConcurrentHashMap<String,String> models;
    private final ConcurrentHashMap<String,LoadedTemplate> templates;
    private RockerClassLoader classLoader;
    
    public ReloadingRockerBootstrap() {
        this(new RockerConfiguration());
    }

    public ReloadingRockerBootstrap(RockerConfiguration configuration) {
        this.configuration = configuration;
        this.models = new ConcurrentHashMap<>();
        this.templates = new ConcurrentHashMap<>();
        this.classLoader = buildClassLoader();
    }

    public void setConfiguration(RockerConfiguration configuration){
        this.configuration = configuration;
    }

    public RockerConfiguration getConfiguration() {
        return configuration;
    }
    
    private RockerClassLoader buildClassLoader() {
        return new RockerClassLoader(this, ReloadingRockerBootstrap.class.getClassLoader());
    }

    // views.index$Template
    // views.index$PlainText
    // views.index$Template$1 (if something like an inner class)
    public boolean isReloadableClass(String className) {
        
        if (this.models.containsKey(className)) {
            return true;
        }
        
        // find first occurrence of $
        int pos = className.indexOf('$');
        if (pos < 0) {
            return false;
        }
        
        String modelClassName = className.substring(0, pos);
        
        return this.templates.containsKey(modelClassName);
    }
    
    public File getTemplateFile(String templatePackageName, String templateName) {
        File templateFileDirectory = new File(this.configuration.getTemplateDirectory(), templatePackageName.replace('.', '/'));
        return new File(templateFileDirectory, templateName);
    }
    
    private long getModelClassModifiedAt(Class modelType) throws RenderingException {
        try {
            Method m = modelType.getMethod("getModifiedAt");
            return (long) m.invoke(null);
        } catch (SecurityException | IllegalArgumentException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RenderingException("Unable to read getModifiedAt static method from class " + modelType.getName());
        }
    }
    
    private String getModelClassHeaderHash(Class modelType) throws RenderingException {
        try {
            Method m = modelType.getMethod("getHeaderHash");
            return (String)m.invoke(null);
        } catch (NoSuchMethodException | SecurityException | IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
            throw new RenderingException("Unable to read getHeaderHash static method from class " + modelType.getName());
        }
    }
    
    private String getModelClassTemplatePackageName(Class modelType) throws RenderingException {
        try {
            Method m = modelType.getMethod("getTemplatePackageName");
            return (String)m.invoke(null);
        } catch (SecurityException | IllegalArgumentException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RenderingException("Unable to read getTemplatePackageName static method from class " + modelType.getName());
        }
    }
    
    private String getModelClassTemplateName(Class modelType) throws RenderingException {
        try {
            Method m = modelType.getMethod("getTemplateName");
            return (String)m.invoke(null);
        } catch (SecurityException | IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RenderingException("Unable to read getTemplateName static method from class " + modelType.getName());
        }
    }
    
    @Override
    public DefaultRockerTemplate template(Class modelType, DefaultRockerModel model) throws RenderingException {
        
        LoadedTemplate template = templates.get(modelType.getName());
        
        if (template == null) {
            // read stored "metadata" compiled with template as static fields
            String templatePackageName = this.getModelClassTemplatePackageName(modelType);
            String templateName = this.getModelClassTemplateName(modelType);
            long modifiedAt = this.getModelClassModifiedAt(modelType);
            String headerHash = this.getModelClassHeaderHash(modelType);
            
            File templateFile = getTemplateFile(templatePackageName, templateName);
            
            if (!templateFile.exists()) {
                log.warn("{}: does not exist for model {}. Unable to check if reload required", templateFile, modelType.getCanonicalName());
                return buildTemplate(modelType, model, this.classLoader);
            }
            
            template = new LoadedTemplate();
            template.file = templateFile;
            template.modifiedAt = modifiedAt;
            template.headerHash = headerHash;
            
            templates.put(modelType.getName(), template);
            
        } else {
            
            if (!template.file.exists()) {
                log.warn("{}: no longer exists for model {} (did you delete it?)", template.file, modelType.getCanonicalName());
                return buildTemplate(modelType, model, this.classLoader);
            }
            
        }

        compileIfNeeded(template, true);
        
        return buildTemplate(modelType, model, this.classLoader);
    }
    
    @Override
    public RockerModel model(String templatePath) throws TemplateNotFoundException, TemplateBindException {
        
        String modelClassName = DefaultRockerBootstrap.templatePathToClassName(templatePath);
        
        LoadedTemplate template = templates.get(modelClassName);
        
        RockerModel initialModel = null;
        
        if (template == null) {
            File templateFile = new File(this.configuration.getTemplateDirectory(), templatePath);
            
            if (!templateFile.exists()) {
                log.warn("{}: does not exist. Unable to check if reload required", templateFile);
                return buildModel(templatePath, this.classLoader);
            }
            
            // load initial model so we can grab the metadata
            template = new LoadedTemplate();
            template.file = templateFile;
            template.modifiedAt = -1;           // maybe its not even compiled yet
            templates.put(modelClassName, template);
            
            // also add to models so that classloader knows to load it
            this.models.put(modelClassName, "");
            
            try {
                // update the template with the initially loaded modifed_at value
                initialModel = buildModel(templatePath, this.classLoader);
                template.modifiedAt = this.getModelClassModifiedAt(initialModel.getClass());
                template.headerHash = this.getModelClassHeaderHash(initialModel.getClass());
            } catch (Exception e) {
                // ignore exceptions here...
            }
            
        } else {
            
            if (!template.file.exists()) {
                log.warn("{}: no longer exists for model {} (did you delete it?)", template.file, modelClassName);
                return buildModel(templatePath, this.classLoader);
            }
            
        }
        
        boolean recompiled = compileIfNeeded(template, false);

        if (initialModel != null && !recompiled) {
            return initialModel;
        } else {
            // build a new one since it was recompiled
            return buildModel(templatePath, this.classLoader);
        }
    }
    
    public boolean compileIfNeeded(LoadedTemplate template, boolean verifyHeaderHash) {
        // recompile needed?
        long modifiedAt = template.file.lastModified();

        if (modifiedAt != template.modifiedAt) {

            log.info("Rocker template change detected [{}]", template.file);

            TemplateCompiler compiler = new TemplateCompiler(this.configuration);

            try {
                long start = System.currentTimeMillis();
                
                List<TemplateCompiler.CompilationUnit> units
                        = compiler.parse(Arrays.asList(template.file));

                // did the interface change?
                TemplateCompiler.CompilationUnit unit = units.get(0);
                String newHeaderHash = unit.getTemplateModel().createHeaderHash()+"";
                
                if (verifyHeaderHash) {
                    if (!newHeaderHash.equals(template.headerHash)) {
                        log.debug("current header hash " + template.headerHash + "; new header hash " + newHeaderHash);
                        
                        // build proper template exception
                        String templatePath = unit.getTemplateModel().getPackageName().replace('.', '/');
                        throw new RenderingException(1, 1, unit.getTemplateModel().getTemplateName(), templatePath, 
                                "Interface (e.g. arguments/imports) were modified. Unable to safely hot reload. Do a fresh project build and JVM restart.", null);
                    }
                }
                
                compiler.generate(units);

                compiler.compile(units);
                
                long stop = System.currentTimeMillis();
                
                log.info("Rocker compiled " + units.size() + " templates in " + (stop - start) + " ms");
                
                // save current modifiedAt & header hash
                template.modifiedAt = modifiedAt;
                template.headerHash = newHeaderHash;   
            } catch (ParserException | CompileDiagnosticException | CompileUnrecoverableException e) {
                throw e;
            } catch (IOException | GeneratorException e) {
                throw new RenderingException("Unable to compile rocker template", e);
            }

            // create new classloader to force a new class load
            this.classLoader = buildClassLoader();
            
            return true;
        }
        
        return false;
    }
}
