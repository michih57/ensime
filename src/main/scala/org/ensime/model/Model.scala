/**
*  Copyright (c) 2010, Aemon Cannon
*  All rights reserved.
*  
*  Redistribution and use in source and binary forms, with or without
*  modification, are permitted provided that the following conditions are met:
*      * Redistributions of source code must retain the above copyright
*        notice, this list of conditions and the following disclaimer.
*      * Redistributions in binary form must reproduce the above copyright
*        notice, this list of conditions and the following disclaimer in the
*        documentation and/or other materials provided with the distribution.
*      * Neither the name of ENSIME nor the
*        names of its contributors may be used to endorse or promote products
*        derived from this software without specific prior written permission.
*  
*  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
*  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
*  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
*  DISCLAIMED. IN NO EVENT SHALL Aemon Cannon BE LIABLE FOR ANY
*  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
*  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
*  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
*  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
*  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
*  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.ensime.model
import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.tools.nsc.interactive.{ Global, CompilerControl }
import scala.tools.nsc.symtab.{ Symbols, Types }
import scala.tools.nsc.util.{ NoPosition, Position }

abstract class EntityInfo(val name: String, val members: Iterable[EntityInfo]) {}

class PackageInfo(override val name: String, val fullname: String, override val members: Iterable[EntityInfo]) extends EntityInfo(name, members) {}

class SymbolSearchResult(
  val name: String,
  val localName: String,
  val declaredAs: scala.Symbol,
  val pos: Option[(String, Int)])
class TypeSearchResult(name: String, localName: String, declaredAs: scala.Symbol, pos: Option[(String, Int)]) extends SymbolSearchResult(name, localName, declaredAs, pos) {}
class MethodSearchResult(name: String, localName: String, declaredAs: scala.Symbol, pos: Option[(String, Int)], val owner: String) extends SymbolSearchResult(name, localName, declaredAs, pos) {}

case class ImportSuggestions(symLists: Iterable[Iterable[SymbolSearchResult]])
case class SymbolSearchResults(syms: Iterable[SymbolSearchResult])

case class SymbolDesignations(
  val file: String,
  val syms: List[SymbolDesignation])

case class SymbolDesignation(
  val start: Int,
  val end: Int,
  val symType: scala.Symbol)


class SymbolInfo(
  val name: String,
  val declPos: Position,
  val tpe: TypeInfo,
  val isCallable: Boolean,
  val ownerTypeId: Option[Int]) {}

case class CompletionInfo(
  val name: String,
  val tpeSig: String,
  val tpeId: Int,
  val isCallable: Boolean,
  val relevance: Int,
  val toInsert: Option[String]) {}

case class CompletionInfoList(
  val prefix: String,
  val completions: List[CompletionInfo]) {}

class NamedTypeMemberInfo(override val name: String, val tpe: TypeInfo, val pos: Position, val declaredAs: scala.Symbol) extends EntityInfo(name, List()) {}

class NamedTypeMemberInfoLight(override val name: String, val tpeSig: String, val tpeId: Int, val isCallable: Boolean) extends EntityInfo(name, List()) {}

class PackageMemberInfoLight(val name: String) {}

class TypeInfo(
  name: String,
  val id: Int,
  val declaredAs: scala.Symbol,
  val fullName: String,
  val args: Iterable[TypeInfo],
  members: Iterable[EntityInfo],
  val pos: Position,
  val outerTypeId: Option[Int]) extends EntityInfo(name, members) {}

class ArrowTypeInfo(
  override val name: String,
  override val id: Int,
  val resultType: TypeInfo,
  val paramSections: Iterable[ParamSectionInfo]) extends TypeInfo(name, id, 'nil, name, List(), List(), NoPosition, None) {}

class CallCompletionInfo(
  val resultType: TypeInfo,
  val paramSections: Iterable[ParamSectionInfo]) {}

class ParamSectionInfo(
  val params: Iterable[(String, TypeInfo)],
  val isImplicit: Boolean)

class InterfaceInfo(val tpe: TypeInfo, val viaView: Option[String]) {}

class TypeInspectInfo(val tpe: TypeInfo, val companionId: Option[Int], val supers: Iterable[InterfaceInfo]) {}

trait ModelBuilders { self: Global with Helpers =>

  import self._
  import definitions.{ ObjectClass, ScalaObjectClass, RootPackage, EmptyPackage, NothingClass, AnyClass, AnyRefClass }

  private val typeCache = new HashMap[Int, Type]
  private val typeCacheReverse = new HashMap[Type, Int]

  def clearTypeCache() {
    typeCache.clear
    typeCacheReverse.clear
  }
  def typeById(id: Int): Option[Type] = {
    typeCache.get(id)
  }
  def cacheType(tpe: Type): Int = {
    if (typeCacheReverse.contains(tpe)) {
      typeCacheReverse(tpe)
    } else {
      val id = typeCache.size + 1
      typeCache(id) = tpe
      typeCacheReverse(tpe) = id
      id
    }
  }

  object ModelHelpers {

    // When inspecting a type, transform a raw list of TypeMembers to a sorted
    // list of InterfaceInfo objects, each with its own list of sorted member infos.
    def prepareSortedInterfaceInfo(members: Iterable[Member]): Iterable[InterfaceInfo] = {
      // ...filtering out non-visible and non-type members
      val visMembers: Iterable[TypeMember] = members.flatMap {
        case m@TypeMember(sym, tpe, true, _, _) => List(m)
        case _ => List()
      }

      // Create a list of pairs [(typeSym, membersOfSym)]
      val membersByOwner = visMembers.groupBy {
        case TypeMember(sym, _, _, _, _) => {
          sym.owner
        }
      }.toList.sortWith {
        // Sort the pairs on the subtype relation
        case ((s1, _), (s2, _)) => s1.tpe <:< s2.tpe
      }

      membersByOwner.map {
        case (ownerSym, members) => {

          // If all the members in this interface were
          // provided by the same view, remember that 
          // view for later display to user.
          val byView = members.groupBy(_.viaView)
          val viaView = if (byView.size == 1) {
            byView.keys.headOption.filter(_ != NoSymbol)
          } else { None }

          // Do one top level sort by name on members, before
          // subdividing into kinds of members.
          val sortedMembers = members.toList.sortWith { (a, b) =>
            a.sym.nameString <= b.sym.nameString
          }

          // Convert type members into NamedTypeMemberInfos
          // and divid into different kinds..

          val nestedTypes = new ArrayBuffer[NamedTypeMemberInfo]()
          val constructors = new ArrayBuffer[NamedTypeMemberInfo]()
          val fields = new ArrayBuffer[NamedTypeMemberInfo]()
          val methods = new ArrayBuffer[NamedTypeMemberInfo]()

          for (tm <- sortedMembers) {
            val info = NamedTypeMemberInfo(tm)
            val decl = info.declaredAs
            if (decl == 'method) {
              if (info.name == "this") {
                constructors += info
              } else {
                methods += info
              }
            } else if (decl == 'field) {
              fields += info
            } else if (decl == 'class || decl == 'trait ||
              decl == 'interface || decl == 'object) {
              nestedTypes += info
            }
          }

          val sortedInfos = nestedTypes ++ fields ++ constructors ++ methods

          new InterfaceInfo(TypeInfo(ownerSym.tpe, sortedInfos),
            viaView.map(_.name.toString))
        }
      }
    }

  }

  import ModelHelpers._

  object PackageInfo {

    def root: PackageInfo = fromSymbol(RootPackage)

    def fromPath(path: String): PackageInfo = {
      val pack = packageSymFromPath(path)
      pack match {
        case Some(packSym) => fromSymbol(packSym)
        case None => nullInfo
      }
    }

    def nullInfo = {
      new PackageInfo("NA", "NA", List())
    }

    def fromSymbol(sym: Symbol): PackageInfo = {
      if (sym.isRoot || sym.isRootPackage) {
        new PackageInfo(
          "root",
          "_root_",
          packageMembers(sym).flatMap(packageMemberInfoFromSym))
      } else {
        new PackageInfo(
          sym.name.toString,
          sym.fullName,
          packageMembers(sym).flatMap(packageMemberInfoFromSym))
      }
    }

    def packageMemberInfoFromSym(sym: Symbol): Option[EntityInfo] = {
      try {
        if (sym == RootPackage) {
          Some(root)
        } else if (sym.isPackage) {
          Some(fromSymbol(sym))
        } else if (!(sym.nameString.contains("$")) && (sym != NoSymbol) && (sym.tpe != NoType)) {
          if (sym.isClass || sym.isTrait || sym.isModule ||
            sym.isModuleClass || sym.isPackageClass) {
            Some(TypeInfo(sym.tpe))
          } else {
            None
          }
        } else {
          None
        }
      } catch {
        case e => None
      }
    }
  }

  object TypeInfo {

    def apply(t: Type, members: Iterable[EntityInfo] = List()): TypeInfo = {
      val tpe = t match {
        // TODO: Instead of throwing away this information, would be better to 
        // alert the user that the type is existentially quantified.
        case et: ExistentialType => et.underlying
        case t => t
      }
      tpe match {
        case tpe: MethodType => ArrowTypeInfo(tpe)
        case tpe: PolyType => ArrowTypeInfo(tpe)
        case tpe: Type =>
        {
          val args = tpe.typeArgs.map(TypeInfo(_))
          val typeSym = tpe.typeSymbol
          val outerTypeId = outerClass(typeSym).map(s => cacheType(s.tpe))
          new TypeInfo(
            typeShortName(tpe),
            cacheType(tpe),
            declaredAs(typeSym),
            typeFullName(tpe),
            args,
            members,
            typeSym.pos,
            outerTypeId)
        }
        case _ => nullInfo
      }
    }

    def nullInfo() = {
      new TypeInfo("NA", -1, 'nil, "NA", List(), List(), NoPosition, None)
    }
  }

  object ParamSectionInfo {
    def apply(params: Iterable[Symbol]): ParamSectionInfo = {
      new ParamSectionInfo(params.map { s => (s.nameString, TypeInfo(s.tpe)) },
        params.forall { s => s.isImplicit })
    }
  }

  object CallCompletionInfo {

    def apply(tpe: Type): CallCompletionInfo = {
      tpe match {
        case tpe: MethodType => apply(tpe.paramss.map(ParamSectionInfo.apply), tpe.finalResultType)
        case tpe: PolyType => apply(tpe.paramss.map(ParamSectionInfo.apply), tpe.finalResultType)
        case _ => nullInfo
      }
    }

    def apply(paramSections: List[ParamSectionInfo], finalResultType: Type): CallCompletionInfo = {
      new CallCompletionInfo(
        TypeInfo(finalResultType),
        paramSections)
    }

    def nullInfo() = {
      new CallCompletionInfo(TypeInfo.nullInfo, List())
    }
  }

  object SymbolInfo {

    def apply(sym: Symbol): SymbolInfo = {
      val name = if (sym.isClass || sym.isTrait || sym.isModule ||
        sym.isModuleClass || sym.isPackageClass) {
        typeFullName(sym.tpe)
      } else {
        sym.nameString
      }
      val ownerTpe = if(sym.owner != NoSymbol && sym.owner.tpe != NoType) {
	Some(sym.owner.tpe)
     } else None
      new SymbolInfo(
        name,
        sym.pos,
        TypeInfo(sym.tpe),
        isArrowType(sym.tpe),
	ownerTpe.map(cacheType)
      )
    }

    def nullInfo() = {
      new SymbolInfo("NA", NoPosition, TypeInfo.nullInfo, false, None)
    }

  }

  object CompletionInfo {

    def apply(sym: Symbol, relevance: Int): CompletionInfo = 
    CompletionInfo(sym, sym.tpe, relevance)

    def apply(sym: Symbol, tpe: Type, relevance: Int): CompletionInfo = {
      new CompletionInfo(
        sym.nameString,
        typeShortNameWithArgs(tpe),
        cacheType(tpe.underlying),
        isArrowType(tpe.underlying),
	relevance,
	None
      )
    }

    def nullInfo() = {
      new CompletionInfo("NA", "NA", -1, false, 0, None)
    }
  }

  object NamedTypeMemberInfo {
    def apply(m: TypeMember): NamedTypeMemberInfo = {
      val decl = declaredAs(m.sym)
      new NamedTypeMemberInfo(m.sym.nameString, TypeInfo(m.tpe), m.sym.pos, decl)
    }
  }

  object NamedTypeMemberInfoLight {
    def apply(m: TypeMember): NamedTypeMemberInfoLight = {
      new NamedTypeMemberInfoLight(m.sym.nameString,
        typeShortNameWithArgs(m.tpe),
        cacheType(m.tpe),
        isArrowType(m.tpe))
    }
  }

  object ArrowTypeInfo {

    def apply(tpe: Type): ArrowTypeInfo = {
      tpe match {
        case tpe: MethodType => apply(tpe, tpe.paramss.map(ParamSectionInfo.apply), tpe.finalResultType)
        case tpe: PolyType => apply(tpe, tpe.paramss.map(ParamSectionInfo.apply), tpe.finalResultType)
        case _ => nullInfo
      }
    }

    def apply(tpe: Type, paramSections: List[ParamSectionInfo], finalResultType: Type): ArrowTypeInfo = {
      new ArrowTypeInfo(
        tpe.toString,
        cacheType(tpe),
        TypeInfo(tpe.finalResultType),
        paramSections)
    }

    def nullInfo() = {
      new ArrowTypeInfo("NA", -1, TypeInfo.nullInfo, List())
    }
  }

  object TypeInspectInfo {
    def nullInfo() = {
      new TypeInspectInfo(TypeInfo.nullInfo, None, List())
    }
  }

}

