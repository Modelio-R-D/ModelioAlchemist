    /**
     * Crée un prompt simple inspiré de ModelioBot - pour génération directe de requirements
     */
    private static String createModelGenerationPrompt(String plantUMLContent, String requirementsDocuments, List<Requirement> parsedRequirements) {
        StringBuilder prompt = new StringBuilder();
        
        // Style ModelioBot : simple et direct
        prompt.append("You are ModelioBot, an AI assistant for Modelio modeling tool.\n\n");
        
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("TASK: Create ALL the following requirements in Modelio using MCP tools:\n\n");
            
            for (Requirement req : parsedRequirements) {
                prompt.append(String.format("- %s: %s (Category: %s, Priority: %s)\n", 
                    req.id, req.description, req.category, req.priority));
            }
            prompt.append("\n");
        } else if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("TASK: Extract requirements from the provided documents and create them in Modelio.\n\n");
        } else {
            prompt.append("TASK: Analyze the PlantUML and create corresponding model elements.\n\n");
        }
        
        if (plantUMLContent != null && !plantUMLContent.trim().isEmpty()) {
            prompt.append("PlantUML diagram:\n```\n");
            prompt.append(plantUMLContent);
            prompt.append("\n```\n\n");
        }
        
        prompt.append("Use the available MCP tools to create each requirement individually. ");
        prompt.append("Create all requirements - do not stop at just one.");
        
        return prompt.toString();
    }