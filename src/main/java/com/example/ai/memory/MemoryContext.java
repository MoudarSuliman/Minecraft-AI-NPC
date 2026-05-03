package com.example.ai.memory;

import java.util.List;

public record MemoryContext(
        List<MemoryEntry> shortTerm,
        List<MemoryEntry> working,
        List<MemoryEntry> longTerm
) {
}
