package com.cosmod.launcherglass.util

internal object DesktopGrid {
    data class Cell(val x:Int,val y:Int,val width:Int,val height:Int)
    val columns=4..6
    val rows=5..8
    fun valid(columns:Int,rows:Int)=columns in this.columns && rows in this.rows
    fun fits(columns:Int,rows:Int,cells:List<Cell>):Boolean {
        if(!valid(columns,rows))return false
        val occupied=BooleanArray(columns*rows)
        for(cell in cells) {
            if(cell.x<0 || cell.y<0 || cell.width<=0 || cell.height<=0 ||
                cell.x>columns-cell.width || cell.y>rows-cell.height)return false
            for(y in cell.y until cell.y+cell.height)for(x in cell.x until cell.x+cell.width) {
                val index=y*columns+x
                if(occupied[index])return false
                occupied[index]=true
            }
        }
        return true
    }
    fun allowsMutation(itemTable:Boolean,screenTable:Boolean,insert:Boolean,update:Boolean,delete:Boolean):Boolean {
        if(listOf(insert,update,delete).count {it}!=1 || itemTable==screenTable)return false
        return if(itemTable)insert || update else insert || delete
    }
}
