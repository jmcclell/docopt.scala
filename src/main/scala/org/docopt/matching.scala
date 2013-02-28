package org.docopt.matching

import scala.{Option => SOption}

import org.docopt.pattern._

object PatternMatcher {
  type SPat = Seq[Pattern]
  type MaybeMatch = SOption[Tuple2[SPat, SPat]]
  type MaybeChild = SOption[Tuple2[Int, Pattern]]

  def matchPattern(matcher: Pattern,
                   left: SPat,
                   collected: SPat = Nil): MaybeMatch = matcher match {
    case child:Argument => matchChildPattern(child, left, collected)
    case child:Command => matchChildPattern(child, left, collected)
    case child:Option => matchChildPattern(child, left, collected)
    case req:Required => matchRequired(req, left, collected)
    case opt:Optional => matchOptional(opt, left, collected)
    case opt:AnyOptions => matchOptional(Optional(Nil), left, collected)
    case eit:Either => matchEither(eit, left, collected)
    case orm:OneOrMore => matchOneOrMore(orm, left, collected)
  }

  private def matchChildPattern(child: Pattern,
                                left: SPat,
                                collected: SPat): MaybeMatch = {
    (child match {
      case a:Argument => matchArgument(a, left)
      case c:Command => matchCommand(c, left)
      case o:Option => matchOption(o, left)
      case _ => None
    }) map {
      case (pos, matched:ChildPattern) =>
        (left.slice(0, pos) ++ left.slice(pos+1, left.length),
         collectSameName(matched, collected))
    }
  }

  private def matchArgument(arg: Argument,
                            left: SPat,
                            index: Int = 0): MaybeChild =
    left match {
      case Nil => None
      case Argument(n, v) :: tail => Some(index, Argument(arg.name, v))
      case head :: tail => matchArgument(arg, tail, index+1)
    }

  private def collectSameName(arg: ChildPattern,
                              collected: SPat): SPat = {
    // http://stackoverflow.com/questions/11394034/why-scalas-pattern-maching-does-not-work-in-for-loops-for-type-matching
    // http://www.scala-lang.org/node/2187
    val sameName = (for (a@(_a:ChildPattern) <- collected;
                         if a.name == arg.name) yield a) toList

    def childPatternUpdateValue(child: ChildPattern, newValue: Value) = child match {
      case Argument(n, _) => Argument(n, newValue)
      case Command(n, _) => Command(n, newValue)
      case Option(s,l,a,_) => Option(s, l, a, newValue)
    }
    // TODO(fsaintjacques): this is nasty branching.
    arg.value match {
      // we must increment the match or set a new to 0
      case IntValue(_) =>
        sameName match {
          // nobody found if match, don't touch anything.
          case Nil =>
            collected ++ List(childPatternUpdateValue(arg, IntValue(1)))
          case head :: tail => head.value match {
            case IntValue(i) =>
              childPatternUpdateValue(head, IntValue(1 + i)) :: tail
          }
        }
      // we must update the list or start a new one.
      case ManyStringValue(s) => List
        sameName match {
          case Nil =>
            collected ++ List(childPatternUpdateValue(arg, arg.value))
          case head :: tail => head.value match {
            case ManyStringValue(s_) =>
              childPatternUpdateValue(head, ManyStringValue(s ++ s_)) :: tail
          }
        }
      case _ => collected ++ List(childPatternUpdateValue(arg, arg.value))
    }
  }

  private def matchCommand(cmd: Command,
                           left: SPat,
                           index: Int = 0): MaybeChild =
    left match {
      case Nil => None
      case Argument(n, StringValue(v)) :: tail if v == cmd.name =>
        Some(index, Command(cmd.name, BooleanValue(true)))
      case (a:Argument) :: tail => None
      case head :: tail => matchCommand(cmd, tail, index+1)
    }

  private def matchOption(opt: Option,
                          left: SPat,
                          index: Int = 0): MaybeChild =
    left match {
      case Nil => None
      case (o:Option) :: tail if o.name == opt.name => Some(index, o)
      case head :: tail => matchOption(opt, tail, index+1)
    }

  private def matchRequired(req: Required,
                            left: SPat,
                            collected: SPat): MaybeMatch =
    req.children.foldLeft(Some(left, collected):MaybeMatch) {
      case (Some((l, c)), child) => matchPattern(child, l, c)
      case (None, child) => None
    }

  private def matchOptional(opt: Optional,
                            left: SPat,
                            collected: SPat): MaybeMatch =
    opt.children.foldLeft(Some(left, collected):MaybeMatch) {
      // this case should never happend, but we need to clear the warning
      case (None, child) => None
      case (Some((l, c)), child) => matchPattern(child, l, c) match {
        // if not matched, still pass the previous (left, collected)
        case None => Some(l, c)
        case Some((l_, c_)) => Some(l_, c_) } }

  private def matchOneOrMore(one: OneOrMore,
                             left: SPat,
                             collected: SPat): MaybeMatch = {
    require(one.children.size == 1)
    val child = one.children(0)
    def whileMatch(p: Pattern, l: SPat, c: SPat): MaybeMatch =
      matchPattern(p, l, c) match {
        // first call and did not match anything
        case None if l.size == left.size => None
        // we matched previously but not anymore
        case None => Some(l, c)
        // Optional never returns None, stop looping if we don't collect
        case Some((l_, c_)) if l.size == l_.size => Some(l_, c_)
        case Some((l_, c_)) => whileMatch(p, l_, c_)
      }
    whileMatch(child, left, collected)
  }

  private def matchEither(either: Either,
                          left: SPat,
                          collected: SPat): MaybeMatch =
    either.children.foldLeft(None:MaybeMatch) {
      case (maybe, child) => {
        matchPattern(child, left, collected) match {
          // this child doesn't match, keep passing the value
          case None => maybe
          // we match, still have work to do
          case newMatch:Some[Tuple2[SPat,SPat]] => maybe match {
            // if previous childs did not match, we're the new matcher
            case None => newMatch
            // an older child matched, but did it collect more element?
            case oldMatch:Some[Tuple2[SPat,SPat]] => (
              if (newMatch.get._1.size < oldMatch.get._1.size) newMatch
              else oldMatch) } } } }
}
