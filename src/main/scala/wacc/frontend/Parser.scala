package wacc.frontend

import wacc.frontend.Lexer._
import wacc.frontend.Lexer.implicits.implicitSymbol
import wacc.AST._
import java.io.File
import parsley.Parsley._
import parsley.{Parsley, Result}
import parsley.combinator.{some, many, sepBy, sepBy1}
import parsley.expr._
import parsley.io.ParseFromIO

object Parser {
  def parse(input: File): Result[String, Program] =
    `<program>`.parseFromFile(input).get
  
  // <program> ::= 'begin' <func>* <stat> 'end'
  private lazy val `<program>` = fully(
    "begin" *> Program(
      many(`<func>`),
      sepBy1(`<stat>`, ";") <* "end"
    )
  )

  // <func> ::= = <type> <ident> ‘(’ <param-list>? ‘)’ ‘is’ <stat> ‘end’
  private lazy val `<func>` = attempt(
    Func(
      `<type>`,
      `<ident>`,
      "(" *> `<param-list>` <* ")",
      "is" *> sepBy1(`<stat>`, ";") <* "end"
    )
  )

  // <param-list> ::= <param> (',' <param>)*
  private lazy val `<param-list>` = sepBy(`<param>`, ",")

  // <param> ::= <type> <ident>
  private lazy val `<param>` = Param(`<type>`, `<ident>`)

   /* <stat> ::= "skip"
               | <type> <ident> "=" <expr>
               | <ident> "=" <expr>
               | "read" <ident>
               | "free" <ident>
               | "return" <expr>
               | "exit" <expr>
               | "print" <expr>
               | "println" <expr>
               | "if" <expr> "then" <stat> "else" <stat> "fi"
               | "while" <expr> "do" <stat> "done"
               | "begin" <stat> "end"
               | <stat> ";" <stat> */
  private lazy val `<stat>` : Parsley[Stat] = (
    Skip <# "skip"
      <|> Declare(`<type>`, `<ident>`, "=" *> `<rvalue>`)
      <|> Assign(`<lvalue>`, "=" *> `<rvalue>`)
      <|> Read("read" *> `<lvalue>`)
      <|> Free("free" *> `<expr>`)
      <|> Return("return" *> `<expr>`)
      <|> Exit("exit" *> `<expr>`)
      <|> Print("print" *> `<expr>`)
      <|> Println("println" *> `<expr>`)
      <|> If(
        "if" *> `<expr>`,
        "then" *> sepBy1(`<stat>`, ";"),
        "else" *> sepBy1(`<stat>`, ";") <* "fi"
      )
      <|> While(
        "while" *> `<expr>`,
        "do" *> sepBy1(`<stat>`, ";") <* "done"
      )
      <|> Scope(
        "begin" *> sepBy1(`<stat>`, ";") <* "end"
      )
  )

  // <lvalue> ::= <ident> | <array-elem> | <pair-elem>
  private lazy val `<lvalue>` : Parsley[LValue] = (
    `<ident>`
      <|> `<array-elem>`
      <|> `<pair-elem>`
  )

  // <pair-elem> ::= "fst" <lvalue> | "snd" <lvalue>
  private lazy val `<pair-elem>` =
    Fst("fst" *> `<lvalue>`) <|> Snd("snd" *> `<lvalue>`)

  // <rvalue> ::= <expr> | <array-liter> | 'newpair' '('' <expr> ',' <expr> ')' | `<pair-elem>` | 'call' <ident> '(' <arg-list> ')'
  private lazy val `<rvalue>` = (
    `<expr>`
      <|> `<array-liter>`
      <|> NewPair("newpair" *> "(" *> `<expr>` <* ",", `<expr>` <* ")")
      <|> `<pair-elem>`
      <|> Call("call" *> `<ident>`, "(" *> `<arg-list>` <* ")")
  )

  // <arg-list> ::= <expr> (‘,’ <expr>)*
  private lazy val `<arg-list>` = sepBy(`<expr>`, ",")
  
  // <type> ::= <base-type> | <array-type> | <pair-type>
  private lazy val `<type>` = chain
    .postfix(`<base-type>` <|> `<pair-type>`, `<array-type>`)

  // <base-type> ::= 'int' | 'bool' | 'char' | 'string'
  private lazy val `<base-type>` = (
    (IntType <# "int")
      <|> (BoolType <# "bool")
      <|> (CharType <# "char")
      <|> (StringType <# "string")
  )

  // <array-type> ::= <type> '[' ']'
  private lazy val `<array-type>` = ArrayType <# ("[" <* "]")

  // <pair-type> ::= ‘pair’ ‘(’ <pair-elem-type> ‘,’ <pair-elem-type> ‘)’
  private lazy val `<pair-type>` = PairType(
    "pair" *> "(" *> `<pair-elem-type>` <* ",",
    `<pair-elem-type>` <* ")"
  )

  // <pair-elem-type> ::= <base-type> | <array-type> | "pair"
  private lazy val `<pair-elem-type>` : Parsley[PairElemType] = attempt(
    chain.postfix1(`<base-type>` <|> `<pair-type>`, ArrayType <# ("[" <* "]"))
  ) <|> `<base-type>` <|> (InnerPairType <# "pair")

  private lazy val `<expr>` : Parsley[Expr] = precedence(
    SOps(InfixR)(Or <# "||") +:
      SOps(InfixR)(And <# "&&") +:
      SOps(InfixN)(
        Equal <# "==",
        NotEqual <# "!="
      )
      +: SOps(InfixN)(
        LT <# "<",
        LTE <# "<=",
        GT <# ">",
        GTE <# ">="
      )
      +: SOps(InfixL)(
        Add <# "+",
        Sub <# "-"
      )
      +: SOps(InfixL)(
        Mult <# "*",
        Div <# "/",
        Mod <# "%"
      )
      +: SOps(Prefix)(
        Not <# "!",
        Negate <# "-",
        Len <# "len",
        Ord <# "ord",
        Chr <# "chr"
      )
      +: Atoms(
        IntegerLiter(INTEGER),
        BoolLiter(BOOL),
        CharLiter(CHAR),
        StrLiter(STRING),
        `<array-elem>`,
        `<ident>`,
        Bracket("(" *> `<expr>` <* ")"),
        PairLiter <# "null"
      )
  )

  // <ident> ::= (‘_’ | ‘a’-‘z’ | ‘A’-‘Z’) (‘–’ | ‘a’-‘z’ | ‘A’-‘Z’ | ‘0’-‘9’)*
  private lazy val `<ident>` = Ident(VAR_ID)

  // <array-elem> ::= <ident> ('[' <expr> ']')+
  private lazy val `<array-elem>` =
      attempt(ArrayElem(`<ident>`, some("[" *> `<expr>` <* "]")))  // TODO: is this correct?

  // <array-liter> ::= '[' (<expr> (',' <expr>)*)? ']'
  private lazy val `<array-liter>` = ArrayLit("[" *> sepBy(`<expr>`, ",") <* "]")

}
