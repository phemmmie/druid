/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.expression;

import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.math.expr.ExprType;
import org.joda.time.Chronology;
import org.joda.time.Period;
import org.joda.time.chrono.ISOChronology;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

public class TimestampShiftExprMacro implements ExprMacroTable.ExprMacro
{
  private static final String FN_NAME = "timestamp_shift";

  @Override
  public String name()
  {
    return FN_NAME;
  }

  @Override
  public Expr apply(final List<Expr> args)
  {
    if (args.size() < 3 || args.size() > 4) {
      throw new IAE("Function[%s] must have 3 to 4 arguments", name());
    }

    if (args.stream().skip(1).allMatch(Expr::isLiteral)) {
      return new TimestampShiftExpr(args);
    } else {
      // Use dynamic impl if any args are non-literal. Don't bother optimizing for the case where period is
      // literal but step isn't.
      return new TimestampShiftDynamicExpr(args);
    }
  }

  private static PeriodGranularity getGranularity(final List<Expr> args, final Expr.ObjectBinding bindings)
  {
    return ExprUtils.toPeriodGranularity(
        args.get(1),
        null,
        args.size() > 3 ? args.get(3) : null,
        bindings
    );
  }

  private static int getStep(final List<Expr> args, final Expr.ObjectBinding bindings)
  {
    return args.get(2).eval(bindings).asInt();
  }

  private static class TimestampShiftExpr extends ExprMacroTable.BaseScalarMacroFunctionExpr
  {
    private final Chronology chronology;
    private final Period period;
    private final int step;

    TimestampShiftExpr(final List<Expr> args)
    {
      super(FN_NAME, args);
      final PeriodGranularity granularity = getGranularity(args, ExprUtils.nilBindings());
      period = granularity.getPeriod();
      chronology = ISOChronology.getInstance(granularity.getTimeZone());
      step = getStep(args, ExprUtils.nilBindings());
    }

    @Nonnull
    @Override
    public ExprEval eval(final ObjectBinding bindings)
    {
      return ExprEval.of(chronology.add(period, args.get(0).eval(bindings).asLong(), step));
    }

    @Override
    public Expr visit(Shuttle shuttle)
    {
      List<Expr> newArgs = args.stream().map(x -> x.visit(shuttle)).collect(Collectors.toList());
      return shuttle.visit(new TimestampShiftExpr(newArgs));
    }

    @Nullable
    @Override
    public ExprType getOutputType(InputBindingInspector inspector)
    {
      return ExprType.LONG;
    }
  }

  private static class TimestampShiftDynamicExpr extends ExprMacroTable.BaseScalarMacroFunctionExpr
  {
    TimestampShiftDynamicExpr(final List<Expr> args)
    {
      super(FN_NAME, args);
    }

    @Nonnull
    @Override
    public ExprEval eval(final ObjectBinding bindings)
    {
      final PeriodGranularity granularity = getGranularity(args, bindings);
      final Period period = granularity.getPeriod();
      final Chronology chronology = ISOChronology.getInstance(granularity.getTimeZone());
      final int step = getStep(args, bindings);
      return ExprEval.of(chronology.add(period, args.get(0).eval(bindings).asLong(), step));
    }

    @Override
    public Expr visit(Shuttle shuttle)
    {
      List<Expr> newArgs = args.stream().map(x -> x.visit(shuttle)).collect(Collectors.toList());
      return shuttle.visit(new TimestampShiftDynamicExpr(newArgs));
    }

    @Nullable
    @Override
    public ExprType getOutputType(InputBindingInspector inspector)
    {
      return ExprType.LONG;
    }
  }
}
