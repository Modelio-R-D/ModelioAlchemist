package com.docaposte.modelioalchemist.handlers.commands;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import com.docaposte.modelioalchemist.langchain.impl.Main;
import com.modeliosoft.modelio.javadesigner.annotations.objid;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.modelio.api.module.IModule;
import org.modelio.api.module.command.DefaultModuleCommandHandler;
import org.modelio.api.module.context.IModuleContext;
import org.modelio.vcore.smkernel.mapi.MObject;

@objid ("08f78cd2-00e3-411d-ac32-e8652d8d6e93")
public class GenerateExigencesForTMA extends DefaultModuleCommandHandler {
    @objid ("0def91bc-afee-4b0f-ba0b-bda01e596b37")
    @Override
    public void actionPerformed(final List<MObject> selectedElements, final IModule module) {
        System.out.println("[TMA] 🚀 Starting TMA command execution...");

        IModuleContext context = module.getModuleContext();
        System.out.println("[TMA] ✅ Module context obtained");

        // Calculer le répertoire de sortie basé sur l'élément sélectionné
        String outputDir;
        try {
            System.out.println("[TMA] 📁 Calculating output directory...");
            outputDir = calculateOutputDirectory(selectedElements, context);
            System.out.println("[TMA] ✅ Output directory calculated: " + outputDir);
        } catch (Exception e) {
            System.err.println("[TMA] ❌ Error calculating output directory: " + e.getMessage());
            e.printStackTrace();
            MessageDialog.openError(Display.getDefault().getActiveShell(), "TMA Configuration Error", 
                "❌ Error calculating output directory: " + e.getMessage());
            return;
        }

        FileDialog dialog = new FileDialog(Display.getDefault().getActiveShell(), SWT.OPEN);
        dialog.setText("Select a TMA requirements document (PDF)");
        dialog.setFilterExtensions(new String[] { "*.pdf", "*.txt" });
        String selected = dialog.open();

        if (selected != null) {
            System.out.println("[TMA] 📄 File selected: " + selected);
            final String selectedFile = selected;
            final String finalOutputDir = outputDir;
            Thread worker = new Thread(() -> {
                try {
                    System.out.println("[TMA] 🔄 Starting TMA pipeline...");
                    Main.tmaWithOutputDir(new String[]{selectedFile}, finalOutputDir);
                    System.out.println("[TMA] ✅ TMA pipeline completed successfully");
                    Display.getDefault().asyncExec(() -> MessageDialog.openInformation(
                        Display.getDefault().getActiveShell(), "TMA Analysis Complete",
                        "✅ TMA requirements analysis generated successfully in: " + finalOutputDir));
                } catch (Exception e) {
                    System.err.println("[TMA] ❌ Pipeline error: " + e.getMessage());
                    e.printStackTrace();
                    Display.getDefault().asyncExec(() -> MessageDialog.openError(
                        Display.getDefault().getActiveShell(), "TMA Analysis Error",
                        "❌ Error during TMA analysis: " + e.getMessage()));
                }
            }, "modelioalchemist-tma-pipeline");
            worker.setDaemon(true);
            worker.start();
        } else {
            System.out.println("[TMA] ⏹️ No file selected, operation cancelled");
        }
    }

    @objid ("dcb47129-511a-423a-a111-309598271fb5")
    @Override
    public boolean accept(final List<MObject> selectedElements, final IModule module) {
        // Generated call to the super method will check the scope conditions defined in Studio.
        // DO NOT REMOVE this call unless you need to take full control on the checks to be carried out.
        // However you can safely extends the checked conditions by adding custom code.
        if (super.accept(selectedElements, module) == false) {
            return false;
        }
        return true;
    }

    /**
     * Calcule le répertoire de sortie basé sur l'élément sélectionné dans Modelio pour TMA
     */
    @objid ("418c1091-1245-492a-bbea-738b6280f3f5")
    private String calculateOutputDirectory(List<MObject> selectedElements, IModuleContext context) {
        System.out.println("[TMA] 📁 Starting calculateOutputDirectory...");

        try {
            // D'abord, essayons de trouver le projet à partir de l'élément sélectionné
            System.out.println("[TMA] 🔍 Checking selected elements: " + selectedElements.size());
    
            if (!selectedElements.isEmpty()) {
                MObject selectedElement = selectedElements.get(0);
                System.out.println("[TMA] 🎯 Selected element: " + selectedElement.getName() + 
                                  " (" + selectedElement.getMClass().getName() + ")");
        
                try {
                    System.out.println("[TMA] 🔎 Getting project path from element...");
                    Path projectPath = getProjectPathFromElement(selectedElement, context);
                    if (projectPath != null) {
                        System.out.println("[TMA] ✅ Project path found: " + projectPath);
                        // Créer un sous-répertoire "tma-analysis-output" dans le projet Modelio
                        String result = projectPath.resolve("tma-analysis-output").toString();
                        System.out.println("[TMA] 📂 Final output directory: " + result);
                        return result;
                    } else {
                        System.out.println("[TMA] ⚠️ Project path is null, trying fallback methods");
                    }
                } catch (Exception e) {
                    System.err.println("[TMA] ❌ Error in getProjectPathFromElement: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Méthode de secours : utiliser la structure du projet du contexte
            System.out.println("[TMA] 🔄 Trying project structure fallback...");
            Path projectRoot = null;
            try {
                projectRoot = context.getProjectStructure().getPath();
                if (projectRoot != null) {
                    System.out.println("[TMA] ✅ Project structure path: " + projectRoot);
                    String result = projectRoot.resolve("tma-analysis-output").toString();
                    System.out.println("[TMA] 📂 Fallback output directory: " + result);
                    return result;
                } else {
                    System.out.println("[TMA] ⚠️ Project structure path is null");
                }
            } catch (Exception e) {
                System.err.println("[TMA] ❌ Error getting project structure: " + e.getMessage());
            }

            // Dernier recours : utiliser le chemin des ressources du module
            System.out.println("[TMA] 🔄 Trying module resources fallback...");
            if (context.getConfiguration() != null) {
                Path moduleRes = context.getConfiguration().getModuleResourcesPath();
                projectRoot = moduleRes != null ? moduleRes.getParent() : null;
                if (projectRoot == null) projectRoot = moduleRes;
                if (projectRoot != null) {
                    System.out.println("[TMA] ✅ Module resources path: " + projectRoot);
                    String result = projectRoot.resolve("tma-analysis-output").toString();
                    System.out.println("[TMA] 📂 Module fallback output directory: " + result);
                    return result;
                } else {
                    System.out.println("[TMA] ⚠️ Module resources path is null");
                }
            } else {
                System.out.println("[TMA] ⚠️ Context configuration is null");
            }
        } catch (Exception e) {
            System.err.println("[TMA] ❌ Error in calculateOutputDirectory: " + e.getMessage());
            e.printStackTrace();
        }

        // Répertoire de secours si aucune méthode ne fonctionne
        String fallbackDir = Paths.get(System.getProperty("user.home"), "tma-analysis-output").toString();
        System.out.println("[TMA] 🏠 Using home directory fallback: " + fallbackDir);
        return fallbackDir;
    }

    /**
     * Récupère le chemin du projet Modelio à partir d'un élément sélectionné
     */
    @objid ("3f8f2b24-dbee-44b1-996b-e4586f645ab2")
    private Path getProjectPathFromElement(MObject element, IModuleContext context) {
        System.out.println("[TMA] 🔎 Starting getProjectPathFromElement...");

        try {
            // Remonter la hiérarchie jusqu'au projet racine
            MObject current = element;
            System.out.println("[TMA] 🔄 Starting hierarchy traversal from: " + current.getName());
    
            while (current != null) {
                System.out.println("[TMA] 🔍 Checking element: " + current.getName() + 
                                  " (" + current.getMClass().getName() + ")");
        
                // Vérifier si c'est un projet
                if (current instanceof org.modelio.metamodel.mda.Project) {
                    System.out.println("[TMA] ✅ Found project instance!");
                    org.modelio.metamodel.mda.Project project = (org.modelio.metamodel.mda.Project) current;

                    // Essayer de récupérer le chemin du projet
                    String projectName = project.getName();
                    System.out.println("[TMA] 📋 Project name: " + projectName);
            
                    if (projectName != null && !projectName.isEmpty()) {
                        // Utiliser le chemin de la structure du projet comme base
                        try {
                            System.out.println("[TMA] 📁 Getting project structure path...");
                            Path projectStructurePath = context.getProjectStructure().getPath();
                            if (projectStructurePath != null) {
                                System.out.println("[TMA] ✅ Project structure path: " + projectStructurePath);
                                // Le répertoire du projet est généralement le parent du .conf
                                return projectStructurePath;
                            } else {
                                System.out.println("[TMA] ⚠️ Project structure path is null");
                            }
                        } catch (Exception e) {
                            System.err.println("[TMA] ❌ Error getting project structure: " + e.getMessage());
                        }
                    }
                    break;
                }

                // Remonter au parent
                current = current.getCompositionOwner();
                if (current == null) {
                    System.out.println("[TMA] 🔚 Reached top of hierarchy");
                }
            }

            // Si on n'a pas trouvé de projet, essayer via la session
            System.out.println("[TMA] 🔄 Trying session-based project discovery...");
            if (context.getModelingSession() != null) {
                var modelRoots = context.getModelingSession().getModel().getModelRoots();
                System.out.println("[TMA] 🌳 Found " + modelRoots.size() + " model roots");
        
                for (var root : modelRoots) {
                    System.out.println("[TMA] 🔍 Checking root: " + root.getName() + 
                                      " (" + root.getMClass().getName() + ")");
                    if (root instanceof org.modelio.metamodel.mda.Project) {
                        System.out.println("[TMA] ✅ Found project in model roots!");
                        try {
                            Path projectStructurePath = context.getProjectStructure().getPath();
                            if (projectStructurePath != null) {
                                System.out.println("[TMA] ✅ Session project structure path: " + projectStructurePath);
                                return projectStructurePath;
                            } else {
                                System.out.println("[TMA] ⚠️ Session project structure path is null");
                            }
                        } catch (Exception e) {
                            System.err.println("[TMA] ❌ Error in session project structure: " + e.getMessage());
                        }
                        break;
                    }
                }
            } else {
                System.out.println("[TMA] ⚠️ Modeling session is null");
            }
        } catch (Exception e) {
            System.err.println("[TMA] ❌ Error in getProjectPathFromElement: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[TMA] ❌ getProjectPathFromElement returning null");
        return null;
    }

}
