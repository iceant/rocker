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
package com.fizzed.rocker.runtime;

import com.fizzed.rocker.BindableRockerModel;
import com.fizzed.rocker.RenderingException;
import com.fizzed.rocker.RockerModel;
import com.fizzed.rocker.RockerTemplate;
import com.fizzed.rocker.TemplateBindException;
import com.fizzed.rocker.TemplateNotFoundException;
import java.lang.reflect.Constructor;

/**
 *
 * @author joelauer
 */

public class DefaultRockerBootstrap implements RockerBootstrap {
    
    public DefaultRockerBootstrap() {
        // nothing to do
    }
    
    protected DefaultRockerTemplate buildTemplate(Class modelType, DefaultRockerModel model, ClassLoader classLoader) throws RenderingException {
        try {
            Class<?> templateType = Class.forName(modelType.getName() + "$Template", false, classLoader);

            Constructor<?> templateConstructor = templateType.getConstructor(modelType);

            return (DefaultRockerTemplate)templateConstructor.newInstance(model);
        } catch (Exception e) {
            throw new RenderingException("Unable to load template class", e);
        }
    }
    
    @Override
    public DefaultRockerTemplate template(Class modelType, DefaultRockerModel model) throws RenderingException {
        
        return buildTemplate(modelType, model, modelType.getClassLoader());

    }
    
    static public String templatePathToClassName(String templateName) {
        if (templateName == null) {
            throw new NullPointerException("Template name was null");
        }
        StringBuilder names = new StringBuilder();

        String[] paths = templateName.split("/");
        for(String path : paths){
            path = path.replace(" ", "_space_");
            path = path.replace(".", "_dot_");
            names.append(path).append(".");
        }
        return names.deleteCharAt(names.length()-1).toString();
    }

    public RockerModel buildModel(String templatePath, ClassLoader classLoader) {
        // views/app/index.rocker.html -> views.app.index
        String modelClassName = templatePathToClassName(templatePath);
        
        Class<?> modelType = null;
        try {
            modelType = Class.forName(modelClassName, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new TemplateNotFoundException("Compiled template " + templatePath + " not found", e);
        }
        
        try {
            return (RockerModel)modelType.newInstance();
        } catch (Exception e) {
            throw new TemplateBindException(templatePath, modelClassName, "Unable to create model for template " + templatePath, e);
        }
    }
    
    @Override
    public RockerModel model(String templatePath) throws TemplateNotFoundException, TemplateBindException {
        
        return buildModel(templatePath, DefaultRockerBootstrap.class.getClassLoader());

    }
}
