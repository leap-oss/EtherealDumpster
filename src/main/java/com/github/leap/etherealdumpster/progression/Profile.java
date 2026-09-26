package com.github.leap.etherealdumpster.progression;

import java.util.*;

public record Profile(UUID player, String name, long rescues, long contributions, String activeTitle,
                      boolean anonymous, Set<String> unlocked, Set<String> notices) {
    public Profile { unlocked = Set.copyOf(unlocked); notices = Set.copyOf(notices); }
}
