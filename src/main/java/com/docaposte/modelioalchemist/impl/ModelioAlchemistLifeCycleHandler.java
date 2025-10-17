package com.docaposte.modelioalchemist.impl;

import java.util.Map;
import com.modeliosoft.modelio.javadesigner.annotations.objid;
import org.modelio.api.module.lifecycle.DefaultModuleLifeCycleHandler;
import org.modelio.api.module.lifecycle.ModuleException;
import org.modelio.vbasic.version.Version;

@objid ("333f99f1-cf93-4c4b-bacf-950bb71fdc68")
public class ModelioAlchemistLifeCycleHandler extends DefaultModuleLifeCycleHandler {
    @objid ("5da3a819-3f28-4b50-b5a5-1eba2871fa67")
    public  ModelioAlchemistLifeCycleHandler(final ModelioAlchemistModule module) {
        super(module);
    }

    @objid ("261c552a-9634-43c5-9279-d69f180feb01")
    @Override
    public boolean start() throws ModuleException {
        return super.start();
    }

    @objid ("693a037a-8e0e-4c4f-82b5-845637e34dee")
    @Override
    public void stop() throws ModuleException {
        super.stop();
    }

    /**
     * @param mdaPath @return
     */
    @objid ("ceb07837-65d6-4c1c-bf0b-f0e91bbc6e19")
    public static boolean install(final String modelioPath, final String mdaPath) throws ModuleException {
        return DefaultModuleLifeCycleHandler.install(modelioPath, mdaPath);
    }

    @objid ("17ee1b25-0ff0-424b-ab1a-128d57382612")
    @Override
    public boolean select() throws ModuleException {
        return super.select();
    }

    @objid ("c716a2cc-8a70-40f6-9deb-8d9e6ccb44c6")
    @Override
    public void upgrade(final Version oldVersion, final Map<String, String> oldParameters) throws ModuleException {
        super.upgrade(oldVersion, oldParameters);
    }

    @objid ("071eb4df-cc39-4e30-8d2a-0c4d8375424f")
    @Override
    public void unselect() throws ModuleException {
        super.unselect();
    }

}
