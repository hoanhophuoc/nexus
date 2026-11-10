package com.haichutieu.chatsystem.server.groups;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroupNameRequest(@NotBlank @Size(max = 120) String name) { }
