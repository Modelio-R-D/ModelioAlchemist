package com.docaposte.modelioalchemist.i18n;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import com.modeliosoft.modelio.javadesigner.annotations.objid;

@objid ("e6fa4621-8552-4935-a5fb-d0f785fef875")
public class Messages {
    @objid ("d265752b-00f5-4098-a2f2-1b3da9a0e4cb")
    private static ResourceBundle RESOURCE_BUNDLE = initializeBundle();

    @objid ("e6fa4621-8552-4935-a5fb-d0f785fef876")
    private static ResourceBundle initializeBundle() {
        try {
            return ResourceBundle.getBundle("com.docaposte.modelioalchemist.i18n.messages");
        } catch (MissingResourceException e) {
            // Fallback to English if locale is not available
            return ResourceBundle.getBundle("com.docaposte.modelioalchemist.i18n.messages", java.util.Locale.ENGLISH);
        }
    }

    @objid ("6f931984-3641-4345-9045-084e08d9eabc")
    private  Messages() {
        
    }

    @objid ("edb45ca5-32d0-4d7a-a907-ee3a4eb405bd")
    public static String getString(final String key) {
        try {
            return RESOURCE_BUNDLE.getString (key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    @objid ("84287cd8-cd2f-407d-930a-d4675295d15f")
    public static String getString(final String key, final String... params) {
        try {
            return MessageFormat.format (RESOURCE_BUNDLE.getString (key),(Object[]) params);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

}
