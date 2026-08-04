package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.task.ExecutionScope;
import io.github.thomas16.minemate.task.Goal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One capability the companion can be asked to perform: its name, its declared parameters, its
 * honest execution scope, and the factory that turns a validated call into a {@link Goal}.
 *
 * @param scope mirrors the scope of the goal this tool builds. A {@link ExecutionScope#CONTINUOUS}
 *              tool never finishes on its own, so a plan may only use one as its final step.
 */
public record ToolDescriptor(String name, String summary, ExecutionScope scope,
        List<ToolParam> parameters, GoalBuilder builder) {

    /**
     * Binds validated arguments to the live world. Runs at step start, not at plan acceptance,
     * because targets move and players log out between the two.
     */
    @FunctionalInterface
    public interface GoalBuilder {
        Goal build(ToolArguments args, ToolContext ctx) throws ToolBindException;
    }

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(builder, "builder");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public boolean continuous() {
        return scope == ExecutionScope.CONTINUOUS;
    }

    public Optional<ToolParam> parameter(String parameterName) {
        return parameters.stream().filter(param -> param.name().equals(parameterName)).findFirst();
    }
}
