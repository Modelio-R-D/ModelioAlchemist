package com.docaposte.modelioalchemist.impl;

import com.modeliosoft.modelio.javadesigner.annotations.objid;
import org.modelio.api.module.AbstractJavaModule;
import org.modelio.api.module.context.IModuleContext;
import org.modelio.api.module.lifecycle.IModuleLifeCycleHandler;
import org.modelio.api.module.parameter.IParameterEditionModel;

@objid ("3c75e949-2ada-4cab-92b5-1fc3c09aa20d")
public class ModelioAlchemistModule extends AbstractJavaModule {
    @objid ("aef34222-a9dd-4785-b901-0ac52d6d1baa")
    private static final String MODULE_IMAGE = "/res/icon/gui/modelioAlchemist16_18_26.ICON.png";

    @objid ("0492b305-b635-4230-b043-6cb72fbbe841")
    private ModelioAlchemistPeerModule peerModule = null;

    @objid ("70f1ba15-efe3-4157-8bce-45ed3cf095e7")
    private ModelioAlchemistLifeCycleHandler lifeCycleHandler = null;

    @objid ("c0c849b5-e929-4f55-b720-0210bee9927c")
    private static ModelioAlchemistModule instance;

    @objid ("b877c28b-a18f-4af5-bc21-5abec767c0ed")
    public  ModelioAlchemistModule(final IModuleContext moduleContext) {
        super(moduleContext);

        ModelioAlchemistModule.instance = this;

        this.lifeCycleHandler  = new ModelioAlchemistLifeCycleHandler(this);
        this.peerModule = new ModelioAlchemistPeerModule(this, moduleContext.getPeerConfiguration());
        init();
    }

    @objid ("fc0dd19c-d0fb-4b30-84a2-559556701f34")
    @Override
    public ModelioAlchemistPeerModule getPeerModule() {
        return this.peerModule;
    }

    /**
     * Return the LifeCycleHandler  attached to the current module. This handler is used to manage the module lifecycle by declaring the desired implementation for the start, select... methods.
     */
    @objid ("3a13b5f4-bc2c-41fc-80a9-f61ea9256268")
    @Override
    public IModuleLifeCycleHandler getLifeCycleHandler() {
        return this.lifeCycleHandler;
    }

    /**
     * Method automatically called just after the creation of the module. The module is automatically instanciated at the beginning
     * of the MDA lifecycle and constructor implementation is not accessible to the module developer. The <code>init</code> method
     * allows the developer to execute the desired initialization.
     */
    @objid ("ab2344f1-bec8-4494-9254-946fd6bd8f9f")
    @Override
    public IParameterEditionModel getParametersEditionModel() {
        return super.getParametersEditionModel();
    }

    @objid ("cd44da1f-bc37-4dc7-893b-14486b33aa6b")
    @Override
    public String getModuleImagePath() {
        return ModelioAlchemistModule.MODULE_IMAGE;
    }

    @objid ("b605ad66-52a6-4faf-816e-25b97c2ea25d")
    public static ModelioAlchemistModule getInstance() {
        // Automatically generated method. Please delete this comment before entering specific code.
        return instance;
    }

}
