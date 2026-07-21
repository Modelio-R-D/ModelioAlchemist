package com.docaposte.modelioalchemist.handlers.commands;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import com.docaposte.modelioalchemist.i18n.Messages;
import com.docaposte.modelioalchemist.langchain.impl.Main;
import com.docaposte.modelioalchemist.langchain.impl.PipelineCancelledException;
import com.docaposte.modelioalchemist.langchain.impl.PipelineProgressListener;
import com.modeliosoft.modelio.javadesigner.annotations.objid;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.modelio.api.module.IModule;
import org.modelio.api.module.command.DefaultModuleCommandHandler;
import org.modelio.api.module.context.IModuleContext;
import org.modelio.vcore.smkernel.mapi.MObject;

@objid ("54f085fa-f3ca-4cb8-a5d4-e43d7391f12b")
public class GenerateFromPdfCommand extends DefaultModuleCommandHandler {
    @objid ("84813144-b357-409d-85af-b8662f1c81aa")
    @Override
    public void actionPerformed(List<MObject> selectedElements, IModule module) {
        IModuleContext context = module.getModuleContext();

        // Calculer le répertoire de sortie basé sur l'élément sélectionné
        String outputDir = calculateOutputDirectory(selectedElements, context);

        FileDialog dialog = new FileDialog(Display.getDefault().getActiveShell(), SWT.OPEN);
        dialog.setText(Messages.getString("filedialog.selectPdf.text"));
        dialog.setFilterExtensions(new String[] { "*.pdf" });
        String selected = dialog.open();

        if (selected != null) {
            final String selectedFile = selected;
            final String finalOutputDir = outputDir;

            ProgressMonitorDialog progressDialog = new ProgressMonitorDialog(Display.getDefault().getActiveShell());
            try {
                progressDialog.run(true, true, monitor -> {
                    monitor.beginTask(Messages.getString("task.generateFromPdf"), IProgressMonitor.UNKNOWN);
                    try {
                        Main.mainWithOutputDir(new String[] { selectedFile }, finalOutputDir,
                            new PipelineProgressListener() {
                                @Override
                                public void onStage(String stage) {
                                    monitor.subTask(stage);
                                }

                                @Override
                                public boolean isCancelled() {
                                    return monitor.isCanceled();
                                }
                            });
                    } catch (PipelineCancelledException e) {
                        throw new InterruptedException(e.getMessage());
                    } catch (Exception e) {
                        throw new InvocationTargetException(e);
                    } finally {
                        monitor.done();
                    }
                });
                MessageDialog.openInformation(
                    Display.getDefault().getActiveShell(), Messages.getString("dialog.done.title"),
                    Messages.getString("dialog.done.message", finalOutputDir));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                cause.printStackTrace();
                MessageDialog.openError(
                    Display.getDefault().getActiveShell(), Messages.getString("dialog.error.title"), cause.getMessage());
            } catch (InterruptedException e) {
                MessageDialog.openInformation(
                    Display.getDefault().getActiveShell(), Messages.getString("dialog.cancelled.title"),
                    Messages.getString("dialog.cancelled.message"));
            }
        }
    }


    @objid ("083fb4d9-a211-47b9-8edf-f7569ae45814")
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
     * Calcule le répertoire de sortie basé sur l'élément sélectionné dans Modelio
     */
    @objid ("9e117774-ddb3-4b4e-9770-d2d788b1e2fb")
    private String calculateOutputDirectory(List<MObject> selectedElements, IModuleContext context) {
        try {
            // D'abord, essayons de trouver le projet à partir de l'élément sélectionné
            if (!selectedElements.isEmpty()) {
                MObject selectedElement = selectedElements.get(0);
                Path projectPath = getProjectPathFromElement(selectedElement, context);
                if (projectPath != null) {
                    // Créer un sous-répertoire "modelioalchemist-output" dans le projet Modelio
                    return projectPath.resolve("modelioalchemist-output").toString();
                }
            }
    
            // Méthode de secours : utiliser la structure du projet du contexte
            Path projectRoot = null;
            try {
                projectRoot = context.getProjectStructure().getPath();
                if (projectRoot != null) {
                    return projectRoot.resolve("modelioalchemist-output").toString();
                }
            } catch (Exception ignored) {}
    
            // Dernier recours : utiliser le chemin des ressources du module
            if (context.getConfiguration() != null) {
                Path moduleRes = context.getConfiguration().getModuleResourcesPath();
                projectRoot = moduleRes != null ? moduleRes.getParent() : null;
                if (projectRoot == null) projectRoot = moduleRes;
                if (projectRoot != null) {
                    return projectRoot.resolve("modelioalchemist-output").toString();
                }
            }
        } catch (Exception e) {
            System.err.println("Erreur lors du calcul du répertoire de sortie: " + e.getMessage());
            e.printStackTrace();
        }

        // Répertoire de secours si aucune méthode ne fonctionne
        return Paths.get(System.getProperty("user.home"), "modelioalchemist-output").toString();
    }

    /**
     * Récupère le chemin du projet Modelio à partir d'un élément sélectionné
     */
    @objid ("17f042f1-0840-4364-85c7-70a98721dfbc")
    private Path getProjectPathFromElement(MObject element, IModuleContext context) {
        try {
            // Remonter la hiérarchie jusqu'au projet racine
            MObject current = element;
            while (current != null) {
                // Vérifier si c'est un projet
                if (current instanceof org.modelio.metamodel.mda.Project) {
                    org.modelio.metamodel.mda.Project project = (org.modelio.metamodel.mda.Project) current;
            
                    // Essayer de récupérer le chemin du projet
                    // Dans Modelio, le projet peut avoir des informations sur son emplacement
                    String projectName = project.getName();
                    if (projectName != null && !projectName.isEmpty()) {
                        // Utiliser le chemin de la structure du projet comme base
                        try {
                            Path projectStructurePath = context.getProjectStructure().getPath();
                            if (projectStructurePath != null) {
                                // Le répertoire du projet est généralement le parent du .conf
                                return projectStructurePath;
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
                }
        
                // Remonter au parent
                current = current.getCompositionOwner();
            }
    
            // Si on n'a pas trouvé de projet, essayer via la session
            if (context.getModelingSession() != null) {
                var modelRoots = context.getModelingSession().getModel().getModelRoots();
                for (var root : modelRoots) {
                    if (root instanceof org.modelio.metamodel.mda.Project) {
                        try {
                            Path projectStructurePath = context.getProjectStructure().getPath();
                            if (projectStructurePath != null) {
                                return projectStructurePath;
                            }
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Erreur lors de la récupération du chemin du projet: " + e.getMessage());
        }

        return null;
    }

}
