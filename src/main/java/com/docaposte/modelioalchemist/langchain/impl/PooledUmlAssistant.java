package com.docaposte.modelioalchemist.langchain.impl;

import dev.langchain4j.memory.ChatMemory;

/**
 * Holder pour une instance UmlModelingAssistant avec sa propre ChatMemory.
 */
class PooledUmlAssistant {
    final ChatMemory memory;
    final UmlModelingAssistant assistant;
    
    PooledUmlAssistant(ChatMemory memory, UmlModelingAssistant assistant) {
        this.memory = memory;
        this.assistant = assistant;
    }
}