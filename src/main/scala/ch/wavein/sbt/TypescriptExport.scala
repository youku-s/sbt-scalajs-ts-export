package ch.wavein.sbt

import scala.meta._

object TypescriptExport {




  def apply(sources: Seq[Source]):String = {

    def definedTypes():Seq[String] = {
      sources.flatMap(skipPackage).filter{
        case cls:Defn.Class => filterMod(cls.mods)
        case _ => false
      }.map{case cls:Defn.Class => cls.name.value}
    }

    def toType(typ:Type,prefix:Boolean = true):String = {
      var pre = {if(prefix) ":"  else "" }
      typ match {
        case t:Type.Function => {
          val params = t.params.map(toType(_,false)).zipWithIndex.map{ case (p,i) => s"x$i:$p"}.mkString("(",",",")")
          pre + s"$params => ${toType(t.res, false)}"
        }
        case t:Type.Name =>  pre + typeMapping(t.value)
        case t:Type.Apply => {
          toType(t.tpe,false) match {
            case "?" => "?:" + toType(t.args.head,false)
            case parentType:String => pre + parentType + "<" + t.args.map(toType(_,false)).mkString(",") + ">"
          }
        }
        case t:Type.Select => toType(t.name,prefix)
      }
    }

    def typeMapping(t:String):String = t match {
      case "String" => "string"
      case "Boolean" => "boolean"
      case "Unit" => "any"
      case "Int" | "Double" | "Long" => "number"
      case "Optional" => "?"
      case "Seq" | "List" => "Array"
      case "Promise" => "Promise"
      case s:String if definedTypes().contains(s) => s
      case _=> {
        println(s"not found type: $t, known types are: ${definedTypes()}")
        "any"
      }
    }

    def toName(n:Type):Option[String] = n match {
      case t:Type.Name => Some(t.value)
      case _ => None
    }

    def filterMod(mods:Seq[Mod]):Boolean = mods.exists{
      case ann:Mod.Annot => toName(ann.init.tpe).contains("TSExport")
      case _ => false
    }

    def consts(obj:Defn.Object):Option[String] = {

      def filterJsExpAll(ann:Mod.Annot) = toName(ann.init.tpe).contains("JSExportTopLevel")

      def arg(ann:Mod.Annot):Option[String] = {
        ann.init.argss.headOption.flatMap(_.headOption).flatMap{
            case l:Lit.String => Some(l.value)
            case _ => None
        }
      }


      val jsExpAll = obj.mods.filter{
        case ann:Mod.Annot => filterJsExpAll(ann)
        case _ => false
      }
      jsExpAll.flatMap{
        case ann:Mod.Annot => arg(ann)
        case _ => None
      }.headOption


    }

    def filterTs(stats:Seq[Stat]):Seq[Stat] = {

      stats.filter{
        case d:Defn.Def => filterMod(d.mods)
        case obj:Defn.Object => filterMod(obj.mods)
        case cls:Defn.Class => filterMod(cls.mods)
        case v:Defn.Val => filterMod(v.mods)
        case _ => false
      }
    }

    def generateConst(stat:Stat):String = {
      val termsToExport = stat match {
        case o: Defn.Object => consts(o).map(a => (o,a))
        case _ => None
      }

      termsToExport.map{case (obj,ann) =>
        s"export const ${ann}:${obj.name.value};"
      }.mkString;

    }

    def generateInterface(stat:Stat):String = {
      val name:String = stat match {
        case o:Defn.Object => o.name.value
        case o:Defn.Class => o.name.value
      }


      val termsToExport: Seq[Tree] = stat match {
        case o:Defn.Object => filterTs(o.templ.stats)
        case o:Defn.Class => {

          o.ctor.paramss.flatten ++
            filterTs(o.templ.stats)
        }
      }

      s"""
         |export interface $name{
         |  ${termsToExport.map(generateTerm).mkString("\n  ")}
         |}
     """.stripMargin.trim

    }

    def generateTerm(stat:Tree):String = {
      stat match {
        case d:Defn.Def => {
          val params = d.paramss.flatten.map(p => s"${p.name}${toType(p.decltpe.get)}").mkString(",")
          val returnType = toType(d.decltpe.get)
          s"""${d.name.value}($params)$returnType;"""
        }
        case v:Defn.Val => {
          val returnType = toType(v.decltpe.get)
          val name = v.pats.headOption match {
            case Some(p:Pat.Var) => p.name.value
            case _ => "noname";
          }
          s"$name$returnType;"
        }
        case t:Term.Param => {
          val returnType = toType(t.decltpe.get)
          s"${t.name.value}$returnType;"
        }
      }
    }


    def skipPackage(s:Source):Seq[Stat] = {
      s.stats.headOption match {
        case Some(p:Pkg) => p.stats
        case _ => s.stats
      }
    }

    filterTs(sources.flatMap(skipPackage)).map{ stat =>
      s"""
         |${generateInterface(stat)}
         |
         |${generateConst(stat)}
         |
       """.stripMargin.trim
    }.mkString("\n\n")


  }



}
