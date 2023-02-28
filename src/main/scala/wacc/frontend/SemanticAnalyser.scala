package wacc.frontend

import wacc.AST._
import wacc.frontend.Errors._

import java.io.File
import scala.collection.mutable

object SemanticAnalyser {

  /** This function is used to check the semantics of a program.
    * @param program
    *   The program to be checked
    * @param source
    *   The source file of the program
    * @return
    *   A list of errors found in the program
    */
  def checkProgramSemantics(
      program: Program
  )(implicit source: File): List[WACCError] = {
    val errors: mutable.ListBuffer[WACCError] = mutable.ListBuffer.empty
    val functionDefs: mutable.Map[Ident, (Type, List[Type])] = mutable.Map.empty
    val localPrintTable = Map.empty[(Int, Int), Type]

    program match {
      case Program(funcs, stats) =>
        
        funcs.foreach(func => {
          if (functionDefs contains func.ident)
            errors += RedefinedFunctionError.genError(func.ident)

          functionDefs += (func.ident -> (func.ty, func.paramList.map(_.ty)))
        })
        implicit val funcTable: Map[Ident, (Type, List[Type])] =
          functionDefs.toMap

        funcs.foreach(func => {
          val paramTable: mutable.Map[Ident, Type] = mutable.Map.empty

          func.paramList.foreach(param => {
            if (paramTable contains param.ident)
              errors += RedefinedVariableError.genError(param.ident)
            paramTable += (param.ident -> param.ty)
          })

          val (statErrors, statPrintTable) = checkStatSemantics(
            paramTable.toMap,
            func.stats,
            Some(func.ty),
          )
          errors ++= statErrors
          localPrintTable ++ statPrintTable
        })

        val (statErrors, statPrintTable) = checkStatSemantics(program.symbolTable, stats, None)
        errors ++= statErrors
        localPrintTable ++ statPrintTable

    }
    program.printTable ++ localPrintTable
    (errors.toList)
  }

  /** This function is used to check the semantics of a list of statements.
    * @param symbolTable
    *   The symbol table of the current scope
    * @param stats
    *   The list of statements to be checked
    * @param returnType
    *   The return type of the current scope, if in a function
    * @param source
    *   The source file of the program
    * @param funcTable
    *   The function table of the program
    * @return
    *   A list of semantic errors found in the list of statements
    */
  private def checkStatSemantics(
      symbolTable: Map[Ident, Type],
      stats: List[Stat],
      returnType: Option[Type]
  )(implicit
      source: File,
      funcTable: Map[Ident, (Type, List[Type])]
  ): (List[WACCError], Map[(Int, Int), Type]) = {
    val errors: mutable.ListBuffer[WACCError] = mutable.ListBuffer.empty
    val printTable: Map[(Int, Int), Type] = Map.empty

    val scopedSymbolTable: mutable.Map[Ident, Type] = mutable.Map.empty
    implicit var curSymbolTable: Map[Ident, Type] =
      symbolTable ++ scopedSymbolTable.toMap

    stats.foreach {
      case Skip() => ()

      case Declare(ty, ident, rvalue) =>
        if (scopedSymbolTable contains ident) {
          errors += RedefinedVariableError.genError(ident)
        } else {
          scopedSymbolTable += (ident -> ty)
          curSymbolTable = symbolTable ++ scopedSymbolTable.toMap
        }

        val (rValueType, rValueErrors, rValPrintTable) = evalTypeOfRValue(rvalue)
        errors ++= rValueErrors
        printTable ++ rValPrintTable
        if (!(rValueType equiv ty))
          errors += TypeMismatchError.genError(
            rValueType,
            Set(ty),
            rvalue.pos,
            s"declaration of variable ${ident.name}"
          )

      case Assign(lValue, rValue) =>
        val (lValueType, lValueErrors, lValPrintTable) = evalTypeOfLValue(lValue)
        val (rValueType, rValueErrors, rValPrintTable) = evalTypeOfRValue(rValue)
        errors ++= lValueErrors
        errors ++= rValueErrors
        printTable ++ lValPrintTable
        printTable ++ rValPrintTable

        (lValueType, rValueType) match {
          case (UnknownType(), UnknownType()) =>
            errors += TypeMismatchError.genError(
              rValueType,
              Set(lValueType),
              rValue.pos,
              "pair element assignment"
            )
          case _ =>
            if (
              !(lValueType equiv rValueType) && (lValueType != ErrorType()(
                NULLPOS
              ))
            )
              errors += TypeMismatchError.genError(
                rValueType,
                Set(lValueType),
                rValue.pos,
                "assignment"
              )
        }

      case Read(lValue) =>
        val (lValueType, lValueErrors, lValPrintTable) = evalTypeOfLValue(lValue)
        errors ++= lValueErrors
        printTable ++ lValPrintTable

        lValueType match {
          case IntType() | CharType() => ()
          case _ =>
            errors += TypeMismatchError.genError(
              lValueType,
              Set(IntType()(NULLPOS), CharType()(NULLPOS)),
              lValue.pos,
              "read"
            )
        }

      case Free(expr) =>
        val (exprType, exprTypeErrors, exprPrintTable) = evalTypeOfExpr(expr)
        errors ++= exprTypeErrors
        printTable ++ exprPrintTable

        exprType match {
          case PairType(_, _) | ArrayType(_) =>
          case _ =>
            errors += TypeMismatchError.genError(
              exprType,
              Set(
                ArrayType(AnyType()(NULLPOS))(NULLPOS),
                PairType(AnyType()(NULLPOS), AnyType()(NULLPOS))(NULLPOS)
              ),
              expr.pos,
              "free"
            )
        }

      case stat @ Return(expr) =>
        val (exprType, exprTypeErrors, exprPrintTable) = evalTypeOfExpr(expr)
        errors ++= exprTypeErrors
        printTable ++ exprPrintTable

        (returnType, exprType) match {
          case (Some(returnType), exprType) =>
            if (!(returnType equiv exprType))
              errors += TypeMismatchError.genError(
                exprType,
                Set(returnType),
                expr.pos,
                "return"
              )
          case (None, _) => errors += UnexpectedReturnError.genError(stat)
        }

      case Exit(expr) =>
        val (exprType, exprTypeErrors, exprPrintTable) = evalTypeOfExpr(expr)
        errors ++= exprTypeErrors
        printTable ++ exprPrintTable
        exprType match {
          case IntType() =>
          case _ =>
            errors += TypeMismatchError.genError(
              exprType,
              Set(IntType()(NULLPOS)),
              expr.pos,
              "exit"
            )
        }

      case Print(expr) =>
        val (exprType, exprTypeErrors, exprPrintTable) = evalTypeOfExpr(expr)
        errors ++= exprTypeErrors
        printTable ++ exprPrintTable
        printTable.+(expr.pos -> exprType)

      case Println(expr) =>
        val (exprType, exprTypeErrors, exprPrintTable) = evalTypeOfExpr(expr)
        errors ++= exprTypeErrors
        printTable ++ exprPrintTable
        printTable.+(expr.pos -> exprType)


      case If(cond, thenStat, elseStat) =>
        val (condType, condTypeErrors, condPrintTable) = evalTypeOfExpr(cond)
        errors ++= condTypeErrors
        printTable ++ condPrintTable

        condType match {
          case BoolType() => ()
          case _ =>
            errors += TypeMismatchError.genError(
              condType,
              Set(BoolType()(NULLPOS)),
              cond.pos,
              "if block"
            )
        }

        curSymbolTable = symbolTable ++ scopedSymbolTable.toMap

        val (thenErrors, thenPrintTable) = checkStatSemantics(
          curSymbolTable,
          thenStat,
          returnType
        )

        errors ++= thenErrors
        printTable ++ thenPrintTable

        val (elseErrors, elsePrintTable) = checkStatSemantics(
          curSymbolTable,
          elseStat,
          returnType
        )

        errors ++= elseErrors
        printTable ++ elsePrintTable

      case While(cond, doStat) =>
        val (condType, condTypeErrors, condPrintTable) = evalTypeOfExpr(cond)
        errors ++= condTypeErrors
        printTable ++ condPrintTable
        condType match {
          case BoolType() => ()
          case _ =>
            errors += TypeMismatchError.genError(
              condType,
              Set(BoolType()(NULLPOS)),
              cond.pos,
              "while block"
            )
        }

        curSymbolTable = symbolTable ++ scopedSymbolTable.toMap

        val (doErrors, doPrintTable) = checkStatSemantics(
          curSymbolTable,
          doStat,
          returnType
        )

        errors ++= doErrors
        printTable ++ doPrintTable

      case Scope(scopeStats) =>
        curSymbolTable = symbolTable ++ scopedSymbolTable.toMap

        val (scopeErrors, scopePrintTable) = checkStatSemantics(
          curSymbolTable,
          scopeStats,
          returnType
        )

        errors ++= scopeErrors
        printTable ++ scopePrintTable
    }

    (errors.toList, printTable.toMap)
  }

  /** Evaluates the type of a left-hand value
    * @param lValue
    *   the left-hand value to evaluate
    * @param source
    *   the source file
    * @param funcTable
    *   the function table
    * @param symbolTable
    *   the symbol table
    * @return
    *   the type of the left-hand value and a list of errors if found
    */
  private def evalTypeOfLValue(lValue: LValue)(implicit
      source: File,
      funcTable: Map[Ident, (Type, List[Type])],
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val errors: mutable.ListBuffer[WACCError] = mutable.ListBuffer.empty
    val printTable: Map[(Int, Int), Type] = Map.empty

    lValue match {
      case ident: Ident =>
        symbolTable get ident match {
          case Some(ty) => (ty, errors.toList, printTable)
          case None =>
            (
              ErrorType()(ident.pos),
              (errors += UndefinedVariableError.genError(ident)).toList,
              printTable
            )
        }
      case ArrayElem(ident, _) =>
        symbolTable get ident match {
          case Some(ArrayType(ty)) => (ty, errors.toList, printTable)
          case Some(ty) =>
            (
              ErrorType()(ident.pos),
              (errors += TypeMismatchError.genError(
                ty,
                Set(ArrayType(AnyType()(NULLPOS))(NULLPOS)),
                ident.pos,
                "array index"
              )).toList, 
              printTable
            )
          case None =>
            (
              ErrorType()(ident.pos),
              (errors += UndefinedVariableError.genError(ident)).toList,
              printTable
            )
        }
      case Fst(l) =>
        evalTypeOfLValue(l) match {
          case (PairType(fstTy, _), lValueErrors, lValPrintTable) =>
            (fstTy.asType, lValueErrors, printTable ++ lValPrintTable)
          case (ty, lValueErrors, lValPrintTable) =>
            (
              ErrorType()(l.pos),
              (errors ++= lValueErrors += TypeMismatchError.genError(
                ty,
                Set(PairType(AnyType()(NULLPOS), AnyType()(NULLPOS))(NULLPOS)),
                l.pos,
                "fst"
              )).toList,
              printTable ++ lValPrintTable
            )
        }
      case Snd(l) =>
        evalTypeOfLValue(l) match {
          case (PairType(_, sndTy), lValueErrors, lValPrintTable) =>
            (sndTy.asType, lValueErrors, printTable ++ lValPrintTable)
          case (ty, lValueErrors, lValPrintTable) =>
            (
              ErrorType()(l.pos),
              (errors ++= lValueErrors += TypeMismatchError.genError(
                ty,
                Set(PairType(AnyType()(NULLPOS), AnyType()(NULLPOS))(NULLPOS)),
                l.pos,
                "snd"
              )).toList,
              printTable ++ lValPrintTable
            )
        }
    }
  }

  /** Evaluates the type of a right-hand value
    * @param rValue
    *   the right-hand value to evaluate
    * @param source
    *   the source file
    * @param funcTable
    *   the function table
    * @param symbolTable
    *   the symbol table
    * @return
    *   the type of the right-hand value and a list of errors if found
    */
  private def evalTypeOfRValue(rValue: RValue)(implicit
      source: File,
      funcTable: Map[Ident, (Type, List[Type])],
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val errors: mutable.ListBuffer[WACCError] = mutable.ListBuffer.empty
    val printTable: Map[(Int, Int), Type] = Map.empty

    rValue match {
      case expr: Expr => evalTypeOfExpr(expr)
      case arrayLit @ ArrayLit(xs) =>
        xs match {
          case Nil =>
            (ArrayType(AnyType()(NULLPOS))(arrayLit.pos), errors.toList, printTable)
          case head :: tail =>
            val (expectedArrElemType, headErrors, localPrintTable) =
              evalTypeOfExpr(head)
            errors ++= headErrors
            printTable ++ localPrintTable

            val (actualArrElemType, tailErrors, arrPrintTable) =
              checkExprs(tail, expectedArrElemType)
            errors ++= tailErrors
            printTable ++ arrPrintTable
            (ArrayType(actualArrElemType)(arrayLit.pos), errors.toList, printTable)
        }
      case NewPair(fst, snd) =>
        val (fstType, fstErrors, fstPrintTable) = evalTypeOfExpr(fst)
        val (sndType, sndErrors, sndPrintTable) = evalTypeOfExpr(snd)
        errors ++= fstErrors
        errors ++= sndErrors
        (
          PairType(fstType.eraseInnerTypes, sndType.eraseInnerTypes)(NULLPOS),
          errors.toList,
          printTable ++ (fstPrintTable ++ sndPrintTable)
        )
      case c @ Call(f, args) =>
        funcTable get f match {
          case Some((_, _)) =>
            val (argTypes, argErrors, argPrintTable) =
              args.map(evalTypeOfExpr(_)).unzip3
            errors ++= argErrors.flatten
            printTable ++ argPrintTable.flatten

            val (returnType, paramTypes) = funcTable(f)
            if (argTypes.length != paramTypes.length)
              errors += IncorrectNumberOfArgsError.genError(
                f,
                argTypes.length,
                paramTypes.length
              )

            argTypes.zip(paramTypes).zipWithIndex.foreach {
              case ((argType, paramType), i) =>
                if (!(argType equiv paramType))
                  errors += TypeMismatchError.genError(
                    argType,
                    Set(paramType),
                    args.toIndexedSeq(i).pos,
                    s"function call to $f, argument $i"
                  )
            }

            (returnType, errors.toList, printTable)
          case None =>
            (
              ErrorType()(c.pos),
              (errors += UndefinedFunctionError.genError(f)).toList,
              printTable
            )
        }
      case Fst(lValue) =>
        val (exprType, error, exprPrintTable) = evalTypeOfLValue(lValue)
        errors ++= error
        printTable ++ exprPrintTable
        exprType match {
          case PairType(fstType, _) => (fstType.asType, errors.toList, printTable)
          case _                    => (ErrorType()(lValue.pos), errors.toList, printTable)
        }
      case Snd(lValue) =>
        val (exprType, error, exprPrintTable) = evalTypeOfLValue(lValue)
        errors ++= error
        printTable ++ exprPrintTable
        exprType match {
          case PairType(_, sndType) => (sndType.asType, errors.toList, printTable)
          case _                    => (ErrorType()(lValue.pos), errors.toList, printTable)
        }
    }
  }

  /** Evaluates the type of an expression
    * @param expr
    *   the expression to evaluate
    * @param source
    *   the source file
    * @param symbolTable
    *   the symbol table
    * @return
    *   the type of the expression and a list of any errors found during
    *   evaluation
    */
  private def evalTypeOfExpr(
      expr: Expr
  )(implicit
      source: File,
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val printTable: Map[(Int, Int), Type] = Map.empty
    expr match {
      case intLit: IntegerLiter => (IntType()(intLit.pos), Nil, printTable)
      case boolLit: BoolLiter   => (BoolType()(boolLit.pos), Nil, printTable)
      case charLit: CharLiter   => (CharType()(charLit.pos), Nil, printTable)
      case strLit: StrLiter     => (StringType()(strLit.pos), Nil, printTable)
      case nullPairLit @ Null() =>
        (
          PairType(AnyType()(nullPairLit.pos), AnyType()(nullPairLit.pos))(
            nullPairLit.pos
          ),
          Nil,
          printTable
        )
      case ident: Ident =>
        if (symbolTable contains ident) {
          (symbolTable(ident).positioned(ident.pos), Nil, printTable)
        } else {
          (ErrorType()(ident.pos), List(UndefinedVariableError.genError(ident)), printTable)
        }
      case ArrayElem(ident, xs) =>
        def getArrayTypeRank(ty: Type): Int = {
          ty match {
            case ArrayType(innerTy) => 1 + getArrayTypeRank(innerTy)
            case _                  => 0
          }
        }

        val errors: mutable.ListBuffer[WACCError] = mutable.ListBuffer.empty

        symbolTable get ident match {
          case Some(t @ ArrayType(innerType)) =>
            val (argTypes, argErrors, pt) =
              xs.map(evalTypeOfExpr(_)).unzip3
            errors ++= argErrors.flatten
            printTable ++ pt.foldLeft(Map.empty: Map[(Int, Int), Type]) {
              (acc, m) => acc ++ m
            }
            if (xs.length > getArrayTypeRank(t))
              (
                ErrorType()(ident.pos),
                (errors += ArrayDimensionMismatchError.genError(
                  xs.length,
                  getArrayTypeRank(t),
                  ident.pos
                )).toList,
                printTable
              )

            argTypes.zipWithIndex.foreach { case (argType, i) =>
              if (!(argType equiv IntType()(NULLPOS)))
                (
                  ErrorType()(ident.pos),
                  errors += TypeMismatchError.genError(
                    argType,
                    Set(IntType()(NULLPOS)),
                    xs.toIndexedSeq(i).pos,
                    s"array access for $ident"
                  ),
                  printTable
                )
            }

            (innerType.positioned(ident.pos), errors.toList, printTable)
          case Some(ot) =>
            (
              ErrorType()(ident.pos),
              (errors += TypeMismatchError.genError(
                ot,
                Set(ArrayType(AnyType()(NULLPOS))(NULLPOS)),
                ident.pos,
                s"array access for $ident"
              )).toList,
              printTable
            )
          case None =>
            (
              ErrorType()(ident.pos),
              (errors += UndefinedVariableError.genError(ident)).toList,
              printTable
            )
        }
      case not @ Not(x) =>
        checkExprType(x, BoolType()(not.pos), BoolType()(not.pos))
      case neg @ Neg(x) =>
        checkExprType(x, IntType()(neg.pos), IntType()(neg.pos))
      case len @ Len(x) =>
        checkExprType(
          x,
          ArrayType(AnyType()(len.pos))(len.pos),
          IntType()(len.pos)
        )
      case ord @ Ord(x) =>
        checkExprType(x, CharType()(ord.pos), IntType()(ord.pos))
      case chr @ Chr(x) =>
        checkExprType(x, IntType()(chr.pos), CharType()(chr.pos))
      case mul @ Mult(x, y) =>
        check2ExprType(Set(IntType()(NULLPOS)), x, y, IntType()(mul.pos))
      case div @ Div(x, y) =>
        check2ExprType(Set(IntType()(NULLPOS)), x, y, IntType()(div.pos))
      case mod @ Mod(x, y) =>
        check2ExprType(Set(IntType()(NULLPOS)), x, y, IntType()(mod.pos))
      case add @ Add(x, y) =>
        check2ExprType(Set(IntType()(NULLPOS)), x, y, IntType()(add.pos))
      case sub @ Sub(x, y) =>
        check2ExprType(Set(IntType()(NULLPOS)), x, y, IntType()(sub.pos))
      case eq @ Equal(x, y) =>
        check2ExprType(Set(AnyType()(NULLPOS)), x, y, BoolType()(eq.pos))
      case neq @ NotEqual(x, y) =>
        check2ExprType(Set(AnyType()(NULLPOS)), x, y, BoolType()(neq.pos))
      case lt @ LT(x, y) =>
        check2ExprType(
          Set(IntType()(NULLPOS), CharType()(NULLPOS)),
          x,
          y,
          BoolType()(lt.pos)
        )
      case lte @ LTE(x, y) =>
        check2ExprType(
          Set(IntType()(NULLPOS), CharType()(NULLPOS)),
          x,
          y,
          BoolType()(lte.pos)
        )
      case and @ And(x, y) =>
        check2ExprType(Set(BoolType()(NULLPOS)), x, y, BoolType()(and.pos))
      case or @ Or(x, y) =>
        check2ExprType(Set(BoolType()(NULLPOS)), x, y, BoolType()(or.pos))
      case gt @ GT(x, y) =>
        check2ExprType(
          Set(IntType()(NULLPOS), CharType()(NULLPOS)),
          x,
          y,
          BoolType()(gt.pos)
        )
      case gte @ GTE(x, y) =>
        check2ExprType(
          Set(IntType()(NULLPOS), CharType()(NULLPOS)),
          x,
          y,
          BoolType()(gte.pos)
        )
      case Bracket(x) => evalTypeOfExpr(x)
    }
  }

  /** Check that an expression is of a certain type
    * @param expr
    *   The expression to check
    * @param expectedType
    *   The expected type
    * @param retType
    *   The type to return if the expression is of the expected type
    * @param source
    *   The source file
    * @param symbolTable
    *   The symbol table
    * @return
    *   The expected type if the expr is of it, or ErrorType otherwise, and a
    *   list of errors
    */
  private def checkExprType(
      expr: Expr,
      expectedType: Type,
      retType: Type // The type of the return value
  )(implicit
      source: File,
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val (exprType, error, printTable) = evalTypeOfExpr(expr)

    if (exprType equiv expectedType) {
      (retType, error, printTable)
    } else {
      (
        ErrorType()(expr.pos),
        error :+ TypeMismatchError.genError(
          exprType,
          Set(expectedType),
          expr.pos,
          ""
        ),
        printTable
      )
    }
  }

  /** Check that two expressions are of the same expected type
    * @param argTypes
    *   The set of expected types
    * @param expr1
    *   The first expression
    * @param expr2
    *   The second expression
    * @param retType
    *   The type of the return value
    * @param source
    *   The source file
    * @param symbolTable
    *   The symbol table
    * @return
    *   The expected type if the expr is of it, or ErrorType otherwise, and a
    *   list of errors
    */
  private def check2ExprType(
      argTypes: Set[Type],
      expr1: Expr,
      expr2: Expr,
      retType: Type
  )(implicit
      source: File,
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val (expr1Type, error1, localPrintTable1) = evalTypeOfExpr(expr1)
    val (expr2Type, error2, localPrintTable2) = evalTypeOfExpr(expr2)
    val printTable = localPrintTable1 ++ localPrintTable2
    val errors = error1 ++ error2

    if (!argTypes.exists(expr1Type equiv _)) {
      (
        ErrorType()(expr1.pos),
        errors :+ TypeMismatchError.genError(
          expr1Type,
          argTypes,
          expr1.pos,
          ""
        ),
        printTable
      )
    } else if (!argTypes.exists(expr2Type equiv _)) {
      (
        ErrorType()(expr2.pos),
        errors :+ TypeMismatchError.genError(
          expr2Type,
          argTypes,
          expr2.pos,
          ""
        ), // Set(expr1Type)
        printTable
      )
    } else if (!((expr1Type equiv expr2Type) || (expr2Type equiv expr1Type))) {
      (
        ErrorType()(expr1.pos),
        errors :+ TypeMismatchError.genError(
          expr1Type,
          Set(expr2Type),
          expr2.pos,
          ""
        ),
        printTable
      )
    } else {

      (retType, errors, printTable)
    }
  }

  /** Check that a list of expressions are of a certain type
    * @param exprs
    *   List of expressions
    * @param expectedType
    *   The expected type of the expressions
    * @param source
    *   The source file
    * @param symbolTable
    *   The symbol table
    * @return
    *   The expected type if the exprs are of it, or ErrorType otherwise, and a
    *   list of errors
    */
  private def checkExprs(
      exprs: List[Expr],
      expectedType: Type
  )(implicit
      source: File,
      symbolTable: Map[Ident, Type]
  ): (Type, List[WACCError], Map[(Int, Int), Type]) = {
    val printTable: Map[(Int, Int), Type] = Map.empty
    exprs match {
      case Nil => (expectedType, Nil, printTable)
      case _ => {
        val evals = exprs.map(checkExprType(_, expectedType, expectedType))
        val types = evals.map(_._1)
        val errors = evals.flatMap(_._2)
        val pts = evals.map(_._3)
        printTable ++ pts.foldLeft(Map.empty: Map[(Int, Int), Type]) {
              (acc, m) => acc ++ m
            }
        if (types.distinct.length == 1 && (types.head equiv expectedType)) {
          (types.head, errors, printTable)
        } else {
          (
            (
              ErrorType()(exprs.head.pos),
              errors,
              printTable
            )
          )
        }
      }
    }
  }
}
