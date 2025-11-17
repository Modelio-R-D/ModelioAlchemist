package com.docaposte.modelioalchemist.impl;

import com.docaposte.modelioalchemist.api.IModelioAlchemistPeerModule;
import com.modeliosoft.modelio.javadesigner.annotations.objid;
import org.modelio.api.module.context.configuration.IModuleAPIConfiguration;
import org.modelio.vbasic.version.Version;

@objid ("422023b2-a3ac-40ed-9605-edcf12850521")
public class ModelioAlchemistPeerModule implements IModelioAlchemistPeerModule {
    @objid ("001bc24d-d0f8-4137-983a-75d2f530dfeb")
    private ModelioAlchemistModule module = null;

    @objid ("f56ad505-a87c-4002-814b-98bdf8359bd3")
    private IModuleAPIConfiguration peerConfiguration;

    @objid ("a6753ab1-c39d-493e-be8a-136ec3c84789")
    public  ModelioAlchemistPeerModule(final ModelioAlchemistModule module, final IModuleAPIConfiguration peerConfiguration) {
        this.module = module;
        this.peerConfiguration = peerConfiguration;
    }

    @objid ("05b9d584-b595-4ca4-a8a1-3c5364cef6b9")
    public IModuleAPIConfiguration getConfiguration() {
        return this.peerConfiguration;
    }

    @objid ("c788f561-5c29-4eea-9e9e-e652fe192217")
    public String getDescription() {
        return this.module.getDescription();
    }

    @objid ("d22e37cd-93d9-44ed-aca0-0f8eb555fba5")
    public String getName() {
        return this.module.getName();
    }

    @objid ("48a88b59-6f6a-4047-a298-b468800bef0f")
    public Version getVersion() {
        return this.module.getVersion();
    }

    @objid ("33e9c61c-1c5d-499b-8324-048b43c6ae10")
    void init() {
        
    }

}
